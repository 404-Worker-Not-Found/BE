package com.workernotfound.job.domain.job.service;

import com.jayway.jsonpath.JsonPath;
import com.workernotfound.job.support.TestSchedule;
import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.ApplicationAdmissionStatus;
import com.workernotfound.job.domain.job.event.ApplicationSubmittedEvent;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.ApplicationAdmissionFixture;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.JobRowLockRace;
import com.workernotfound.job.support.MutableClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 지원 접수 승인 만료 배치와 {@code ApplicationSubmitted} 소비가 어떤 순서로 실행돼도 올바른 상태로 수렴하는지 검증한다.
 *
 * <p>소비자는 Redis Stream 소비자가 레코드마다 호출하는 트랜잭션 단위({@link JobAdmissionEventReceiptService#receive})로 실행한다.
 * 두 처리는 같은 승인 행 잠금으로 직렬화되므로, 경합 테스트는 한쪽을 커밋 직전에 멈추고 다른 쪽이 그 잠금을 기다리는지 확인한다.
 */
@AutoConfigureMockMvc
class JobApplicationAdmissionExpiryRaceTests extends IntegrationTestSupport {

    private static final String INTERNAL_SECRET = "test-internal-secret";
    private static final String ADMISSION_TABLE = "job_application_admissions";
    private static final Long WORKER_ID = 400L;
    private static final AtomicLong APPLICATION_IDS = new AtomicLong(900_000);

    // 저장 정밀도(마이크로초)에 맞춘 만료 시각. 다른 테스트가 남긴 먼 미래(2030년) 승인은 배치 대상이 되지 않는다.
    private final LocalDateTime expiresAt = TestSchedule.today().plusDays(1).atTime(12, 0, 0, 123_456_000);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JobApplicationAdmissionExpiryReconciler reconciler;

    @Autowired
    private JobApplicationAdmissionExpiryService expiryService;

    @Autowired
    private JobAdmissionEventReceiptService receiptService;

    @Autowired
    private JobApplicationAdmissionRepository admissionRepository;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MockMvc mockMvc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        clock.reset();
    }

    @Test
    void lateEventSubmittedBeforeExpiryConsumesExpiredAdmission() {
        JobApplicationAdmission admission = saveAdmission();
        expireByBatch(admission);
        ApplicationSubmittedEvent event = eventFor(admission, expiresAt.minusMinutes(1));

        AdmissionEventOutcome outcome = receiptService.receive(event);

        assertThat(outcome).isEqualTo(new AdmissionEventOutcome(AdmissionConsumptionResult.CONSUMED, false));
        assertConsumedAt(admission, event.occurredAt());
    }

    @Test
    void batchDoesNotOverwriteAdmissionConsumedFirst() {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent event = eventFor(admission, expiresAt.minusMinutes(1));
        receiptService.receive(event);
        fixAt(expiresAt.plusMinutes(10));

        assertThat(expiryService.expireIfOverdue(admission.getId())).isFalse();
        reconcileUntilIdle();

        assertConsumedAt(admission, event.occurredAt());
    }

    // 소비자가 승인 행을 잠근 채 커밋 전이면 배치는 그 잠금을 기다리고, 커밋 뒤 CONSUMED를 보고 바꾸지 않는다.
    @Test
    void batchWaitingOnConsumerLockKeepsConsumedAdmission() throws Exception {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent event = eventFor(admission, expiresAt.minusMinutes(1));
        fixAt(expiresAt);

        JobRowLockRace.Outcome<AdmissionEventOutcome, Boolean> outcome = newRace().run(ADMISSION_TABLE,
                admission.getId(), () -> receiptService.receive(event), () -> expiryService.expireIfOverdue(admission.getId()));

        assertThat(outcome.first().result()).isEqualTo(AdmissionConsumptionResult.CONSUMED);
        assertThat(outcome.contender().get()).isFalse();
        assertConsumedAt(admission, event.occurredAt());
    }

    // 배치가 승인 행을 잠근 채 커밋 전이면 소비자는 그 잠금을 기다리고, 커밋 뒤 EXPIRED를 만료 전 지원으로 CONSUMED로 바꾼다.
    @Test
    void consumerWaitingOnBatchLockConsumesExpiredAdmission() throws Exception {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent event = eventFor(admission, expiresAt.minusMinutes(1));
        fixAt(expiresAt);

        JobRowLockRace.Outcome<Boolean, AdmissionEventOutcome> outcome = newRace().run(ADMISSION_TABLE,
                admission.getId(), () -> expiryService.expireIfOverdue(admission.getId()), () -> receiptService.receive(event));

        assertThat(outcome.first()).isTrue();
        assertThat(outcome.contender().get())
                .isEqualTo(new AdmissionEventOutcome(AdmissionConsumptionResult.CONSUMED, false));
        assertConsumedAt(admission, event.occurredAt());
    }

    // 배치가 먼저 커밋하면 만료 시각과 같거나 늦은 지원은 경합 뒤에도 EXPIRED를 바꾸지 않는다.
    @Test
    void consumerWaitingOnBatchLockKeepsExpiredForSubmissionAtExpiry() throws Exception {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent event = eventFor(admission, expiresAt);
        fixAt(expiresAt);

        JobRowLockRace.Outcome<Boolean, AdmissionEventOutcome> outcome = newRace().run(ADMISSION_TABLE,
                admission.getId(), () -> expiryService.expireIfOverdue(admission.getId()), () -> receiptService.receive(event));

        assertThat(outcome.first()).isTrue();
        assertThat(outcome.contender().get().result()).isEqualTo(AdmissionConsumptionResult.SUBMITTED_AFTER_EXPIRY);
        assertExpired(admission);
    }

    @Test
    void submissionAtOrAfterExpiryDoesNotConsumeExpiredAdmission() {
        JobApplicationAdmission admission = saveAdmission();
        expireByBatch(admission);

        assertThat(receiptService.receive(eventFor(admission, expiresAt)).result())
                .isEqualTo(AdmissionConsumptionResult.SUBMITTED_AFTER_EXPIRY);
        assertThat(receiptService.receive(eventFor(admission, expiresAt.plus(1, ChronoUnit.MICROS))).result())
                .isEqualTo(AdmissionConsumptionResult.SUBMITTED_AFTER_EXPIRY);

        assertExpired(admission);
    }

    @Test
    void duplicateEventsAndBatchRerunsKeepFirstConsumedAt() {
        JobApplicationAdmission admission = saveAdmission();
        expireByBatch(admission);
        ApplicationSubmittedEvent event = eventFor(admission, expiresAt.minusMinutes(2));
        receiptService.receive(event);

        assertThat(receiptService.receive(event))
                .isEqualTo(new AdmissionEventOutcome(AdmissionConsumptionResult.CONSUMED, true));
        assertThat(receiptService.receive(eventFor(admission, expiresAt.minusMinutes(1))))
                .isEqualTo(new AdmissionEventOutcome(AdmissionConsumptionResult.ALREADY_CONSUMED, false));
        fixAt(expiresAt.plusHours(1));
        assertThat(expiryService.expireIfOverdue(admission.getId())).isFalse();
        reconcileUntilIdle();
        reconcileUntilIdle();

        assertConsumedAt(admission, event.occurredAt());
    }

    // 실제 발급 API로 받은 승인이 배치로 만료되고, 늦은 이벤트로 사용 완료된 뒤에도 같은 키 재요청 계약은 그대로다.
    @Test
    void sameKeyRetryKeepsAdmissionExpiredContractAcrossExpiryAndLateConsumption() throws Exception {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().build()));
        String key = "expiry-race-" + UUID.randomUUID();
        Long admissionId = issuedAdmissionId(requestAdmission(jobPost.getId(), key, WORKER_ID)
                .andExpect(status().isOk()));
        requestAdmission(jobPost.getId(), key, WORKER_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.admissionId").value(admissionId));
        JobApplicationAdmission issued = admissionRepository.findById(admissionId).orElseThrow();

        fixAt(issued.getExpiresAt());
        assertThat(expiryService.expireIfOverdue(admissionId)).isTrue();
        assertSameKeyRetryRejected(jobPost.getId(), key);

        // 현재 matching-service는 승인의 admittedAt을 occurredAt으로 발행한다.
        AdmissionEventOutcome outcome = receiptService.receive(eventFor(issued, issued.getAdmittedAt()));
        assertThat(outcome.result()).isEqualTo(AdmissionConsumptionResult.CONSUMED);
        assertSameKeyRetryRejected(jobPost.getId(), key);
        requestAdmission(jobPost.getId(), key, WORKER_ID + 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-004"));
    }

    private void assertSameKeyRetryRejected(Long jobPostId, String key) throws Exception {
        requestAdmission(jobPostId, key, WORKER_ID)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-003"));
    }

    private ResultActions requestAdmission(Long jobPostId, String key, Long workerMemberId) throws Exception {
        return mockMvc.perform(post("/api/jobs/internal/{jobPostId}/application-admissions", jobPostId)
                .header("X-Internal-Secret", INTERNAL_SECRET)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workerMemberId\":" + workerMemberId + "}"));
    }

    private Long issuedAdmissionId(ResultActions result) throws Exception {
        Number admissionId = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.admissionId");
        return admissionId.longValue();
    }

    private void expireByBatch(JobApplicationAdmission admission) {
        fixAt(expiresAt);
        reconcileUntilIdle();
        assertExpired(admission);
    }

    private void reconcileUntilIdle() {
        int idleRuns = 0;
        for (int run = 0; run < 1_000 && idleRuns < 2; run++) {
            idleRuns = reconciler.reconcile() == 0 ? idleRuns + 1 : 0;
        }
    }

    private JobRowLockRace newRace() {
        return new JobRowLockRace(transactionManager, entityManager, executor);
    }

    private void fixAt(LocalDateTime time) {
        clock.fixAt(time.atZone(clock.getZone()).toInstant());
    }

    private JobApplicationAdmission saveAdmission() {
        return ApplicationAdmissionFixture.saveReserved(jobPostRepository, admissionRepository, WORKER_ID, expiresAt);
    }

    private ApplicationSubmittedEvent eventFor(JobApplicationAdmission admission, LocalDateTime occurredAt) {
        return new ApplicationSubmittedEvent(UUID.randomUUID().toString(), APPLICATION_IDS.incrementAndGet(), 1L,
                admission.getId(), admission.getJobPostId(), admission.getWorkerMemberId(), occurredAt);
    }

    private void assertConsumedAt(JobApplicationAdmission admission, LocalDateTime expectedConsumedAt) {
        JobApplicationAdmission stored = admissionRepository.findById(admission.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ApplicationAdmissionStatus.CONSUMED);
        assertThat(stored.getConsumedAt()).isEqualTo(expectedConsumedAt);
        assertThat(stored.getConsumedAt()).isBefore(stored.getExpiresAt());
    }

    private void assertExpired(JobApplicationAdmission admission) {
        JobApplicationAdmission stored = admissionRepository.findById(admission.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ApplicationAdmissionStatus.EXPIRED);
        assertThat(stored.getConsumedAt()).isNull();
    }
}
