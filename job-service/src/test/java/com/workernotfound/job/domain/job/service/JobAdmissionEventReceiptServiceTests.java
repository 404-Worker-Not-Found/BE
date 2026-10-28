package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobAdmissionEventReceipt;
import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.enums.AdmissionConsumptionResult;
import com.workernotfound.job.domain.job.entity.enums.ApplicationAdmissionStatus;
import com.workernotfound.job.domain.job.event.ApplicationSubmittedEvent;
import com.workernotfound.job.domain.job.exception.InvalidAdmissionEventException;
import com.workernotfound.job.domain.job.repository.JobAdmissionEventReceiptRepository;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.ApplicationAdmissionFixture;
import com.workernotfound.job.support.IntegrationTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobAdmissionEventReceiptServiceTests extends IntegrationTestSupport {

    private static final Long WORKER_ID = 200L;
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2030, 1, 1, 12, 0, 0, 123_456_000);
    private static final AtomicLong APPLICATION_IDS = new AtomicLong(700_000);
    private static final int CONCURRENT_DELIVERIES = 4;
    private static final String ADMISSION_LOCK_WAIT_QUERY = """
            SELECT 1
            FROM performance_schema.data_lock_waits waits
            JOIN performance_schema.data_locks requested
              ON requested.ENGINE = waits.ENGINE
             AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
            WHERE requested.OBJECT_NAME = 'job_application_admissions'
              AND requested.LOCK_STATUS = 'WAITING'
            """;

    private final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_DELIVERIES);

    @Autowired
    private JobAdmissionEventReceiptService receiptService;

    @Autowired
    private JobAdmissionEventReceiptRepository receiptRepository;

    @Autowired
    private JobApplicationAdmissionRepository admissionRepository;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterAll
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void consumesAdmissionAndRecordsResultInFirstDelivery() {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent event = eventFor(admission, EXPIRES_AT.minusMinutes(1));

        AdmissionEventOutcome outcome = receiptService.receive(event);

        assertThat(outcome).isEqualTo(new AdmissionEventOutcome(AdmissionConsumptionResult.CONSUMED, false));
        assertConsumedAt(admission.getId(), event.occurredAt());
        JobAdmissionEventReceipt receipt = receiptRepository.findByEventId(event.eventId()).orElseThrow();
        assertThat(receipt.getResult()).isEqualTo(AdmissionConsumptionResult.CONSUMED);
        assertThat(receipt.getAdmissionId()).isEqualTo(admission.getId());
        assertThat(receipt.getApplicationId()).isEqualTo(event.applicationId());
        assertThat(receipt.getOccurredAt()).isEqualTo(event.occurredAt());
        assertThat(receipt.getReceivedAt()).isNotNull();
    }

    @Test
    void duplicateDeliveryReturnsStoredResultWithoutChangingAdmission() {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent event = eventFor(admission, EXPIRES_AT.minusMinutes(2));
        receiptService.receive(event);

        AdmissionEventOutcome duplicate = receiptService.receive(event);

        assertThat(duplicate).isEqualTo(new AdmissionEventOutcome(AdmissionConsumptionResult.CONSUMED, true));
        assertConsumedAt(admission.getId(), event.occurredAt());
        assertThat(receiptCount(event.eventId())).isEqualTo(1);
    }

    @Test
    void anotherEventForConsumedAdmissionIsRecordedWithoutChangingConsumedAt() {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent first = eventFor(admission, EXPIRES_AT.minusMinutes(2));
        receiptService.receive(first);

        AdmissionEventOutcome second = receiptService.receive(eventFor(admission, EXPIRES_AT.minusMinutes(1)));

        assertThat(second).isEqualTo(new AdmissionEventOutcome(AdmissionConsumptionResult.ALREADY_CONSUMED, false));
        assertConsumedAt(admission.getId(), first.occurredAt());
    }

    @Test
    void sameEventIdWithDifferentContentIsRefusedWithoutRecording() {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent original = eventFor(admission, EXPIRES_AT.minusMinutes(1));
        receiptService.receive(original);
        ApplicationSubmittedEvent changed = new ApplicationSubmittedEvent(
                original.eventId(), original.applicationId(), original.revision() + 1, original.admissionId(),
                original.jobPostId(), original.workerMemberId(), original.occurredAt());

        assertThatThrownBy(() -> receiptService.receive(changed))
                .isInstanceOf(InvalidAdmissionEventException.class);

        JobAdmissionEventReceipt receipt = receiptRepository.findByEventId(original.eventId()).orElseThrow();
        assertThat(receipt.getRevision()).isEqualTo(original.revision());
        assertThat(receiptCount(original.eventId())).isEqualTo(1);
    }

    @Test
    void recordsPermanentRejectionsWithoutChangingAdmission() {
        JobApplicationAdmission admission = saveAdmission();
        LocalDateTime occurredAt = EXPIRES_AT.minusMinutes(1);

        assertRecordedRejection(event(null, admission.getJobPostId(), WORKER_ID, occurredAt),
                AdmissionConsumptionResult.REJECTED_MISSING_ADMISSION_ID);
        assertRecordedRejection(event(admission.getId(), admission.getJobPostId(), WORKER_ID, null),
                AdmissionConsumptionResult.REJECTED_MISSING_OCCURRED_AT);
        assertRecordedRejection(event(999_999_999L, admission.getJobPostId(), WORKER_ID, occurredAt),
                AdmissionConsumptionResult.REJECTED_ADMISSION_NOT_FOUND);
        assertRecordedRejection(event(admission.getId(), admission.getJobPostId() + 1, WORKER_ID, occurredAt),
                AdmissionConsumptionResult.REJECTED_JOB_POST_MISMATCH);
        assertRecordedRejection(event(admission.getId(), admission.getJobPostId(), WORKER_ID + 1, occurredAt),
                AdmissionConsumptionResult.REJECTED_WORKER_MISMATCH);

        assertUnchanged(admission.getId());
    }

    @Test
    void recordsSubmissionAtExpiryWithoutChangingAdmission() {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent event = eventFor(admission, EXPIRES_AT);

        AdmissionEventOutcome outcome = receiptService.receive(event);

        assertThat(outcome.result()).isEqualTo(AdmissionConsumptionResult.SUBMITTED_AFTER_EXPIRY);
        assertThat(receiptRepository.findByEventId(event.eventId()).orElseThrow().getResult())
                .isEqualTo(AdmissionConsumptionResult.SUBMITTED_AFTER_EXPIRY);
        assertUnchanged(admission.getId());
    }

    // 승인 변경 뒤 수신 기록 저장이 실패하면(eventId가 컬럼 길이를 넘음) 승인 변경도 함께 롤백된다.
    @Test
    void receiptFailureRollsBackAdmissionChange() {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent tooLongEventId = new ApplicationSubmittedEvent(
                "e".repeat(129), APPLICATION_IDS.incrementAndGet(), 1L, admission.getId(),
                admission.getJobPostId(), WORKER_ID, EXPIRES_AT.minusMinutes(1));

        assertThatThrownBy(() -> receiptService.receive(tooLongEventId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertUnchanged(admission.getId());
        ApplicationSubmittedEvent retry = eventFor(admission, EXPIRES_AT.minusMinutes(1));
        assertThat(receiptService.receive(retry).result()).isEqualTo(AdmissionConsumptionResult.CONSUMED);
    }

    // 같은 eventId가 동시에 전달되면 하나만 승인을 바꾸고 기록한다. 늦은 처리는 유일 제약으로 롤백되거나 중복으로 판단된다.
    @Test
    void concurrentDuplicateDeliveriesChangeAdmissionOnce() throws Exception {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent event = eventFor(admission, EXPIRES_AT.minusMinutes(1));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AdmissionEventOutcome>> deliveries = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_DELIVERIES; i++) {
            deliveries.add(executor.submit(() -> {
                start.await();
                return receiptService.receive(event);
            }));
        }
        start.countDown();

        List<AdmissionEventOutcome> outcomes = collectOutcomes(deliveries);

        assertThat(outcomes).filteredOn(outcome -> !outcome.isDuplicate())
                .containsExactly(new AdmissionEventOutcome(AdmissionConsumptionResult.CONSUMED, false));
        assertThat(outcomes).filteredOn(AdmissionEventOutcome::isDuplicate)
                .allMatch(outcome -> outcome.result() == AdmissionConsumptionResult.CONSUMED);
        assertThat(receiptCount(event.eventId())).isEqualTo(1);
        assertConsumedAt(admission.getId(), event.occurredAt());
    }

    // 첫 처리가 커밋 전이면 늦은 처리는 승인 행 잠금을 기다린다. 첫 처리가 커밋되면 늦은 처리는 수신 기록 유일 제약으로 롤백된다.
    @Test
    void lateDeliveryWaitsForFirstCommitAndRollsBackOnUniqueEventId() throws Exception {
        JobApplicationAdmission admission = saveAdmission();
        ApplicationSubmittedEvent event = eventFor(admission, EXPIRES_AT.minusMinutes(1));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch firstProcessed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Future<AdmissionEventOutcome> first = executor.submit(() -> transaction.execute(status -> {
            AdmissionEventOutcome outcome = receiptService.receive(event);
            firstProcessed.countDown();
            await(releaseFirst);
            return outcome;
        }));
        assertThat(firstProcessed.await(30, TimeUnit.SECONDS)).isTrue();

        Future<AdmissionEventOutcome> late = executor.submit(() -> receiptService.receive(event));
        awaitAdmissionLockWait();
        releaseFirst.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS).result()).isEqualTo(AdmissionConsumptionResult.CONSUMED);
        assertThatThrownBy(() -> late.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
        assertThat(receiptService.receive(event))
                .isEqualTo(new AdmissionEventOutcome(AdmissionConsumptionResult.CONSUMED, true));
        assertThat(receiptCount(event.eventId())).isEqualTo(1);
        assertConsumedAt(admission.getId(), event.occurredAt());
    }

    private void awaitAdmissionLockWait() throws Exception {
        try (Connection observer = openLockObserverConnection();
             PreparedStatement statement = observer.prepareStatement(ADMISSION_LOCK_WAIT_QUERY)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("늦은 처리가 승인 행 잠금을 기다리지 않았습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 대기 시간이 지났습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private List<AdmissionEventOutcome> collectOutcomes(List<Future<AdmissionEventOutcome>> deliveries)
            throws InterruptedException, TimeoutException {
        List<AdmissionEventOutcome> outcomes = new ArrayList<>();
        for (Future<AdmissionEventOutcome> delivery : deliveries) {
            try {
                outcomes.add(delivery.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(DataIntegrityViolationException.class);
            }
        }
        return outcomes;
    }

    private void assertRecordedRejection(ApplicationSubmittedEvent event, AdmissionConsumptionResult expected) {
        AdmissionEventOutcome outcome = receiptService.receive(event);

        assertThat(outcome).isEqualTo(new AdmissionEventOutcome(expected, false));
        assertThat(expected.isRejected()).isTrue();
        assertThat(receiptRepository.findByEventId(event.eventId()).orElseThrow().getResult()).isEqualTo(expected);
    }

    private JobApplicationAdmission saveAdmission() {
        return ApplicationAdmissionFixture.saveReserved(jobPostRepository, admissionRepository, WORKER_ID, EXPIRES_AT);
    }

    private ApplicationSubmittedEvent eventFor(JobApplicationAdmission admission, LocalDateTime occurredAt) {
        return event(admission.getId(), admission.getJobPostId(), admission.getWorkerMemberId(), occurredAt);
    }

    private ApplicationSubmittedEvent event(
            Long admissionId,
            Long jobPostId,
            Long workerMemberId,
            LocalDateTime occurredAt
    ) {
        return new ApplicationSubmittedEvent(UUID.randomUUID().toString(), APPLICATION_IDS.incrementAndGet(), 1L,
                admissionId, jobPostId, workerMemberId, occurredAt);
    }

    private long receiptCount(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_admission_event_receipts WHERE event_id = ?", Long.class, eventId);
    }

    private void assertConsumedAt(Long admissionId, LocalDateTime expectedConsumedAt) {
        JobApplicationAdmission stored = admissionRepository.findById(admissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ApplicationAdmissionStatus.CONSUMED);
        assertThat(stored.getConsumedAt()).isEqualTo(expectedConsumedAt);
    }

    private void assertUnchanged(Long admissionId) {
        JobApplicationAdmission stored = admissionRepository.findById(admissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ApplicationAdmissionStatus.RESERVED);
        assertThat(stored.getConsumedAt()).isNull();
    }
}
