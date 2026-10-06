package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.FundingStatusNotification;
import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.JobCloseRequest;
import com.workernotfound.job.domain.job.entity.JobFundingStatusReceipt;
import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.JobStatusHistory;
import com.workernotfound.job.domain.job.entity.enums.ApplicationAdmissionStatus;
import com.workernotfound.job.domain.job.entity.enums.FundingSkipReason;
import com.workernotfound.job.domain.job.entity.enums.FundingStatusResult;
import com.workernotfound.job.domain.job.entity.enums.JobCloseResult;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentRefundReviewRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.JobStatusHistoryRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.JobRowLockRace;
import com.workernotfound.job.support.MutableClock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 점주 수동 마감과 같은 공고 행 잠금을 쓰는 명령(자리 확정·예약, 지원 승인, 예치 상태 수신, 자동 마감)의 경쟁을 양쪽 실행 순서로 검증한다.
 * 결과는 커밋 순서로만 결정되며, 어느 순서에서도 상태 이력과 모집 종료 알림 명령은 한 번만 남는다.
 */
class JobCloseRaceTests extends IntegrationTestSupport {

    private static final AtomicLong SEQUENCE = new AtomicLong(1_300_000);
    private static final long AMOUNT = 90_000L;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JobCloseCommandService closeService;

    @Autowired
    private JobScheduleTransitionService transitionService;

    @Autowired
    private MatchingSeatReservationCommandService seatService;

    @Autowired
    private JobApplicationAdmissionCommandService admissionService;

    @Autowired
    private JobFundingStatusCommandService fundingService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobMatchingSeatReservationRepository reservationRepository;

    @Autowired
    private JobApplicationAdmissionRepository admissionRepository;

    @Autowired
    private JobStatusHistoryRepository historyRepository;

    @Autowired
    private RecruitmentCompletionCommandRepository commandRepository;

    @Autowired
    private JobPaymentRefundReviewRepository refundReviewRepository;

    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdown();
        try {
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
            clock.reset();
        }
    }

    @Test
    void confirmationWaitingBehindCloseIsRejectedAndSeatCanBeReleased() throws Exception {
        JobPost jobPost = saveJob(JobStatus.OPEN, 2);
        JobMatchingSeatReservation reserved = reserve(jobPost);

        var outcome = race(jobPost, () -> close(jobPost),
                () -> seatService.confirm(jobPost.getId(), reserved.getId(), newKey()));

        assertThat(outcome.first().getResult()).isEqualTo(JobCloseResult.CLOSED);
        assertRejected(outcome.contender(), JobErrorCode.JOB_NOT_MATCHABLE);
        JobMatchingSeatReservation unchanged = reservationRepository.findById(reserved.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(MatchingSeatReservationStatus.RESERVED);
        assertThat(unchanged.getConfirmIdempotencyKey()).isNull();
        assertThat(seatService.release(jobPost.getId(), reserved.getId(), newKey()).getStatus())
                .isEqualTo(MatchingSeatReservationStatus.RELEASED);
        assertClosedOnce(jobPost, JobStatus.OPEN, JobCloseCommandService.MANUAL_CLOSE_REASON);
    }

    @Test
    void confirmationCommittedBeforeCloseIsPreserved() throws Exception {
        JobPost jobPost = saveJob(JobStatus.OPEN, 2);
        JobMatchingSeatReservation reserved = reserve(jobPost);
        String confirmKey = newKey();

        var outcome = race(jobPost, () -> seatService.confirm(jobPost.getId(), reserved.getId(), confirmKey),
                () -> close(jobPost));

        assertThat(outcome.contender().get().getResult()).isEqualTo(JobCloseResult.CLOSED);
        assertThat(seatService.confirm(jobPost.getId(), reserved.getId(), confirmKey).getStatus())
                .isEqualTo(MatchingSeatReservationStatus.CONSUMED);
        assertClosedOnce(jobPost, JobStatus.OPEN, JobCloseCommandService.MANUAL_CLOSE_REASON);
    }

    @Test
    void lastConfirmationBeforeCloseCompletesRecruitmentAndCloseFindsItClosed() throws Exception {
        JobPost jobPost = saveJob(JobStatus.MATCHING, 1);
        JobMatchingSeatReservation reserved = reserve(jobPost);

        var outcome = race(jobPost, () -> seatService.confirm(jobPost.getId(), reserved.getId(), newKey()),
                () -> close(jobPost));

        JobCloseRequest request = outcome.contender().get();
        assertThat(request.getResult()).isEqualTo(JobCloseResult.ALREADY_CLOSED);
        assertThat(request.getPreviousStatus()).isEqualTo(JobStatus.CLOSED);
        assertClosedOnce(jobPost, JobStatus.MATCHING, JobRecruitmentCompletionService.RECRUITMENT_FILLED_REASON);
    }

    @Test
    void admissionWaitingBehindCloseIsRejected() throws Exception {
        JobPost jobPost = saveJob(JobStatus.OPEN, 1);
        String key = newKey();

        var outcome = race(jobPost, () -> close(jobPost), () -> admissionService.create(jobPost.getId(), nextId(), key));

        assertRejected(outcome.contender(), JobErrorCode.JOB_NOT_OPEN);
        assertThat(admissionRepository.findByIdempotencyKey(key)).isEmpty();
        assertClosedOnce(jobPost, JobStatus.OPEN, JobCloseCommandService.MANUAL_CLOSE_REASON);
    }

    @Test
    void closeDoesNotTouchAdmissionCommittedBeforeIt() throws Exception {
        JobPost jobPost = saveJob(JobStatus.OPEN, 1);

        var outcome = race(jobPost, () -> admissionService.create(jobPost.getId(), nextId(), newKey()),
                () -> close(jobPost));

        assertThat(outcome.contender().get().getResult()).isEqualTo(JobCloseResult.CLOSED);
        JobApplicationAdmission admission = admissionRepository.findById(outcome.first().getId()).orElseThrow();
        assertThat(admission.getStatus()).isEqualTo(ApplicationAdmissionStatus.RESERVED);
        assertThat(admission.getExpiresAt()).isEqualTo(outcome.first().getExpiresAt());
        assertClosedOnce(jobPost, JobStatus.OPEN, JobCloseCommandService.MANUAL_CLOSE_REASON);
    }

    @Test
    void reservationWaitingBehindCloseIsRejected() throws Exception {
        JobPost jobPost = saveJob(JobStatus.MATCHING, 1);
        String key = newKey();

        var outcome = race(jobPost, () -> close(jobPost), () -> reserve(jobPost, key));

        assertRejected(outcome.contender(), JobErrorCode.JOB_NOT_MATCHABLE);
        assertThat(reservationRepository.findByIdempotencyKey(key)).isEmpty();
        assertClosedOnce(jobPost, JobStatus.MATCHING, JobCloseCommandService.MANUAL_CLOSE_REASON);
    }

    @Test
    void reservationCommittedBeforeCloseCannotBeConfirmedButCanBeReleased() throws Exception {
        JobPost jobPost = saveJob(JobStatus.OPEN, 1);

        var outcome = race(jobPost, () -> reserve(jobPost, newKey()), () -> close(jobPost));

        assertThat(outcome.contender().get().getResult()).isEqualTo(JobCloseResult.CLOSED);
        JobMatchingSeatReservation reserved = outcome.first();
        assertThatThrownBy(() -> seatService.confirm(jobPost.getId(), reserved.getId(), newKey()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(JobErrorCode.JOB_NOT_MATCHABLE));
        assertThat(seatService.release(jobPost.getId(), reserved.getId(), newKey()).getStatus())
                .isEqualTo(MatchingSeatReservationStatus.RELEASED);
        assertClosedOnce(jobPost, JobStatus.OPEN, JobCloseCommandService.MANUAL_CLOSE_REASON);
    }

    @Test
    void fundingWaitingBehindCloseIsSkippedForRefundReview() throws Exception {
        JobPost jobPost = saveLinkedPaymentPendingJob();

        var outcome = race(jobPost, () -> close(jobPost), () -> receiveFunded(jobPost));

        JobFundingStatusReceipt receipt = outcome.contender().get();
        assertThat(receipt.getResult()).isEqualTo(FundingStatusResult.PUBLICATION_SKIPPED);
        assertThat(receipt.getSkipReason()).isEqualTo(FundingSkipReason.JOB_CLOSED);
        assertThat(receipt.isRefundReviewRequired()).isTrue();
        assertThat(refundReviewRepository.existsByOrderId(jobPost.getPaymentOrderId())).isTrue();
        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.CLOSED);
        // 공개된 적 없는 결제 대기 공고의 마감은 matching-service에 알리지 않는다.
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).isEmpty();
        assertThat(histories(jobPost)).hasSize(1);
    }

    @Test
    void fundingCommittedBeforeClosePublishesAndCloseNotifiesMatching() throws Exception {
        JobPost jobPost = saveLinkedPaymentPendingJob();

        var outcome = race(jobPost, () -> receiveFunded(jobPost), () -> close(jobPost));

        assertThat(outcome.first().getResult()).isEqualTo(FundingStatusResult.PUBLISHED);
        JobCloseRequest request = outcome.contender().get();
        assertThat(request.getResult()).isEqualTo(JobCloseResult.CLOSED);
        assertThat(request.getPreviousStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(histories(jobPost)).extracting(JobStatusHistory::getToStatus)
                .containsExactly(JobStatus.OPEN, JobStatus.CLOSED);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).singleElement()
                .satisfies(command -> assertThat(command.getJobVersion()).isEqualTo(reload(jobPost).getVersion()));
        assertThat(refundReviewRepository.existsByOrderId(jobPost.getPaymentOrderId())).isFalse();
    }

    @Test
    void autoCloseWaitingBehindManualCloseChangesNothing() throws Exception {
        JobPost jobPost = saveJob(JobStatus.MATCHING, 1);
        fixAtWorkStart(jobPost);

        var outcome = race(jobPost, () -> close(jobPost), () -> transitionService.applyIfDue(jobPost.getId()));

        assertThat(outcome.first().getResult()).isEqualTo(JobCloseResult.CLOSED);
        assertThat(outcome.contender().get()).isFalse();
        assertClosedOnce(jobPost, JobStatus.MATCHING, JobCloseCommandService.MANUAL_CLOSE_REASON);
    }

    @Test
    void manualCloseWaitingBehindAutoCloseFindsItClosed() throws Exception {
        JobPost jobPost = saveJob(JobStatus.OPEN, 1);
        fixAtWorkStart(jobPost);

        var outcome = race(jobPost, () -> transitionService.applyIfDue(jobPost.getId()), () -> close(jobPost));

        assertThat(outcome.first()).isTrue();
        assertThat(outcome.contender().get().getResult()).isEqualTo(JobCloseResult.ALREADY_CLOSED);
        assertClosedOnce(jobPost, JobStatus.OPEN, JobScheduleTransitionService.WORK_STARTED_REASON);
    }

    private <F, C> JobRowLockRace.Outcome<F, C> race(JobPost jobPost, Supplier<F> first, Supplier<C> contender)
            throws Exception {
        return new JobRowLockRace(transactionManager, entityManager, executor).run(jobPost.getId(), first, contender);
    }

    // 상태 이력과 모집 종료 알림 명령이 각각 한 번만 남고, 명령의 버전은 마감 후 공고 버전이다.
    private void assertClosedOnce(JobPost jobPost, JobStatus fromStatus, String reason) {
        JobPost closed = reload(jobPost);
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(histories(jobPost)).singleElement().satisfies(history -> {
            assertThat(history.getFromStatus()).isEqualTo(fromStatus);
            assertThat(history.getToStatus()).isEqualTo(JobStatus.CLOSED);
            assertThat(history.getReason()).isEqualTo(reason);
        });
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).singleElement()
                .satisfies(command -> assertThat(command.getJobVersion()).isEqualTo(closed.getVersion()));
    }

    private void assertRejected(Future<?> contender, JobErrorCode expected) {
        assertThatThrownBy(() -> contender.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(BusinessException.class)
                .satisfies(failure -> assertThat(((BusinessException) failure.getCause()).getErrorCode())
                        .isEqualTo(expected));
    }

    private JobCloseRequest close(JobPost jobPost) {
        return closeService.close(jobPost.getId(), jobPost.getOwnerId(), newKey());
    }

    private JobFundingStatusReceipt receiveFunded(JobPost jobPost) {
        FundingStatusNotification notification = new FundingStatusNotification(
                jobPost.getPaymentOrderId(), 1L, jobPost.getOwnerId(), AMOUNT, "KRW", 1L, true);
        return fundingService.receive(jobPost.getId(), notification, newKey());
    }

    private JobMatchingSeatReservation reserve(JobPost jobPost) {
        return reserve(jobPost, newKey());
    }

    private JobMatchingSeatReservation reserve(JobPost jobPost, String key) {
        long id = nextId();
        return seatService.reserve(jobPost.getId(), id, id, id, key);
    }

    private List<JobStatusHistory> histories(JobPost jobPost) {
        return historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId());
    }

    private void fixAtWorkStart(JobPost jobPost) {
        LocalDateTime workStart = jobPost.getWorkDate().atTime(jobPost.getStartTime());
        clock.fixAt(workStart.atZone(clock.getZone()).toInstant());
    }

    private JobPost reload(JobPost jobPost) {
        return jobPostRepository.findById(jobPost.getId()).orElseThrow();
    }

    private JobPost saveJob(JobStatus status, int recruitCount) {
        return jobPostRepository.save(JobPostFixture.withStatus(
                JobPostFixture.jobPost().ownerId(nextId()).recruitCount(recruitCount).build(), status));
    }

    // 결제 스냅샷을 연결한 결제 대기 공고. 주문 생성·연결 경로는 결제 주문 테스트가 검증하므로 여기서는 경쟁만 본다.
    private JobPost saveLinkedPaymentPendingJob() {
        JobPost jobPost = JobPostFixture.jobPost().ownerId(nextId()).build();
        jobPost.linkPaymentOrder(UUID.randomUUID().toString(), 1L, AMOUNT, "KRW");
        return jobPostRepository.save(jobPost);
    }

    private static long nextId() {
        return SEQUENCE.incrementAndGet();
    }

    private static String newKey() {
        return "close-race-" + UUID.randomUUID();
    }
}
