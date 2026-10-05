package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.FundingStatusNotification;
import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.FundingStatusResult;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 예치 차단(funded=false)과 신규 지원 승인·자리 예약·최초 확정의 경쟁을 양쪽 실행 순서로 검증한다.
 *
 * <p>먼저 실행한 트랜잭션이 쓰기 후 커밋 직전에 멈춘 동안 경쟁 트랜잭션이 같은 공고 행 잠금을 기다리는지 MySQL 잠금 대기로 확인한 뒤
 * 첫 트랜잭션을 커밋한다. 두 명령이 같은 공고 행 잠금으로 직렬화되므로 결과는 커밋 순서로만 결정된다.
 */
class FundingBlockRaceTests extends IntegrationTestSupport {

    private static final String JOB_LOCK_WAIT_QUERY = """
            SELECT 1
            FROM performance_schema.data_lock_waits waits
            JOIN performance_schema.data_locks requested
              ON requested.ENGINE = waits.ENGINE
             AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
            JOIN performance_schema.threads requester
              ON requester.THREAD_ID = waits.REQUESTING_THREAD_ID
            JOIN performance_schema.threads blocker
              ON blocker.THREAD_ID = waits.BLOCKING_THREAD_ID
            WHERE requester.PROCESSLIST_ID = ?
              AND blocker.PROCESSLIST_ID = ?
              AND requested.OBJECT_SCHEMA = ?
              AND requested.OBJECT_NAME = 'job_posts'
              AND requested.INDEX_NAME = 'PRIMARY'
              AND requested.LOCK_TYPE = 'RECORD'
              AND requested.LOCK_MODE LIKE 'X%'
              AND requested.LOCK_STATUS = 'WAITING'
              AND requested.LOCK_DATA = ?
            """;
    private static final AtomicLong EXTERNAL_ID_SEQUENCE = new AtomicLong(970_000);
    private static final long AMOUNT = 90_000L;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JobFundingStatusCommandService fundingService;

    @Autowired
    private JobApplicationAdmissionCommandService admissionService;

    @Autowired
    private MatchingSeatReservationCommandService seatService;

    @Autowired
    private JobApplicationAdmissionRepository admissionRepository;

    @Autowired
    private JobMatchingSeatReservationRepository reservationRepository;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private CountDownLatch firstWritten;
    private CountDownLatch releaseFirst;
    private long firstConnectionId;
    private CompletableFuture<Long> contenderConnectionId;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        firstWritten = new CountDownLatch(1);
        releaseFirst = new CountDownLatch(1);
        contenderConnectionId = new CompletableFuture<>();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        releaseFirst.countDown();
        executor.shutdown();
        try {
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsNewAdmissionWaitingBehindBlock() throws Exception {
        JobPost jobPost = saveOpenLinkedJob();
        String key = newKey();

        Future<JobApplicationAdmission> admission = blockFirstThen(jobPost,
                () -> admissionService.create(jobPost.getId(), 100L, key));

        assertRejected(admission, JobErrorCode.JOB_NOT_OPEN);
        assertThat(admissionRepository.findByIdempotencyKey(key)).isEmpty();
    }

    @Test
    void keepsAdmissionCommittedBeforeBlock() throws Exception {
        JobPost jobPost = saveOpenLinkedJob();
        String key = newKey();

        JobApplicationAdmission issued = commandFirstThenBlock(jobPost,
                () -> admissionService.create(jobPost.getId(), 100L, key));

        assertThat(admissionService.create(jobPost.getId(), 100L, key).getId()).isEqualTo(issued.getId());
        assertThatThrownBy(() -> admissionService.create(jobPost.getId(), 100L, newKey()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(JobErrorCode.JOB_NOT_OPEN));
    }

    @Test
    void rejectsNewReservationWaitingBehindBlock() throws Exception {
        JobPost jobPost = saveOpenLinkedJob();
        String key = newKey();

        Future<JobMatchingSeatReservation> reservation = blockFirstThen(jobPost, () -> reserve(jobPost, key));

        assertRejected(reservation, JobErrorCode.JOB_NOT_MATCHABLE);
        assertThat(reservationRepository.findByIdempotencyKey(key)).isEmpty();
    }

    @Test
    void rejectsLaterConfirmationOfReservationCommittedBeforeBlock() throws Exception {
        JobPost jobPost = saveOpenLinkedJob();
        String key = newKey();

        JobMatchingSeatReservation reserved = commandFirstThenBlock(jobPost, () -> reserve(jobPost, key));

        assertThat(seatService.reserve(jobPost.getId(), reserved.getMatchingId(), reserved.getApplicationId(),
                reserved.getWorkerMemberId(), key).getId()).isEqualTo(reserved.getId());
        assertThatThrownBy(() -> seatService.confirm(jobPost.getId(), reserved.getId(), newKey()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(JobErrorCode.JOB_NOT_MATCHABLE));
        // 거절된 확정 뒤 Saga 보상으로 자리를 반환할 수 있다.
        assertThat(seatService.release(jobPost.getId(), reserved.getId(), newKey()).getStatus())
                .isEqualTo(MatchingSeatReservationStatus.RELEASED);
    }

    @Test
    void rejectsConfirmationWaitingBehindBlock() throws Exception {
        JobPost jobPost = saveOpenLinkedJob();
        JobMatchingSeatReservation reserved = reserve(jobPost, newKey());
        String confirmKey = newKey();

        Future<JobMatchingSeatReservation> confirmation = blockFirstThen(jobPost,
                () -> seatService.confirm(jobPost.getId(), reserved.getId(), confirmKey));

        assertRejected(confirmation, JobErrorCode.JOB_NOT_MATCHABLE);
        JobMatchingSeatReservation unchanged = reservationRepository.findById(reserved.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(MatchingSeatReservationStatus.RESERVED);
        assertThat(unchanged.getConfirmIdempotencyKey()).isNull();
    }

    @Test
    void preservesConfirmationCommittedBeforeBlock() throws Exception {
        JobPost jobPost = saveOpenLinkedJob();
        JobMatchingSeatReservation reserved = reserve(jobPost, newKey());
        String confirmKey = newKey();

        commandFirstThenBlock(jobPost, () -> seatService.confirm(jobPost.getId(), reserved.getId(), confirmKey));

        // 확정 결과는 예치 차단 뒤에도 같은 키 재요청에 그대로 유지되고, 반환·취소되지 않는다.
        assertThat(seatService.confirm(jobPost.getId(), reserved.getId(), confirmKey).getStatus())
                .isEqualTo(MatchingSeatReservationStatus.CONSUMED);
        assertThatThrownBy(() -> seatService.release(jobPost.getId(), reserved.getId(), newKey()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(JobErrorCode.SEAT_RESERVATION_STATE_CONFLICT));
    }

    // 예치 차단을 먼저 쓰고 커밋 직전에 멈춘 뒤, 경쟁 명령이 공고 행 잠금을 기다리는지 확인하고 커밋한다.
    private <T> Future<T> blockFirstThen(JobPost jobPost, Supplier<T> contender) throws Exception {
        Future<?> block = executor.submit(() -> holdBeforeCommit(() -> receiveBlock(jobPost)));
        awaitLatch(firstWritten);
        Future<T> contending = executor.submit(() -> runContender(contender));
        assertWaitingForJobLock(contending, jobPost.getId());
        releaseFirst.countDown();
        block.get(10, TimeUnit.SECONDS);
        assertThat(jobPostRepository.findById(jobPost.getId()).orElseThrow().isFundingBlocked()).isTrue();
        return contending;
    }

    // 지원 승인·자리 예약·확정을 먼저 쓰고 커밋 직전에 멈춘 뒤, 예치 차단이 같은 잠금을 기다렸다가 적용되는지 확인한다.
    private <T> T commandFirstThenBlock(JobPost jobPost, Supplier<T> command) throws Exception {
        Future<T> first = executor.submit(() -> holdBeforeCommit(command));
        awaitLatch(firstWritten);
        Future<FundingStatusResult> block = executor.submit(() -> runContender(() -> receiveBlock(jobPost)));
        assertWaitingForJobLock(block, jobPost.getId());
        releaseFirst.countDown();
        T result = first.get(10, TimeUnit.SECONDS);
        assertThat(block.get(10, TimeUnit.SECONDS)).isEqualTo(FundingStatusResult.FUNDING_BLOCKED);
        assertThat(jobPostRepository.findById(jobPost.getId()).orElseThrow().isFundingBlocked()).isTrue();
        return result;
    }

    private FundingStatusResult receiveBlock(JobPost jobPost) {
        FundingStatusNotification notification = new FundingStatusNotification(
                jobPost.getPaymentOrderId(), 1L, jobPost.getOwnerId(), AMOUNT, "KRW", 2L, false);
        return fundingService.receive(jobPost.getId(), notification, newKey()).getResult();
    }

    private JobMatchingSeatReservation reserve(JobPost jobPost, String key) {
        long id = EXTERNAL_ID_SEQUENCE.incrementAndGet();
        return seatService.reserve(jobPost.getId(), id, id, 100L, key);
    }

    // 공고 행에 결제 스냅샷을 연결한 공개 공고. 공개 경로는 API 테스트가 검증하므로 여기서는 경쟁만 본다.
    private JobPost saveOpenLinkedJob() {
        JobPost jobPost = JobPostFixture.open(JobPostFixture.jobPost().recruitCount(2).build());
        jobPost.linkPaymentOrder(UUID.randomUUID().toString(), 1L, AMOUNT, "KRW");
        return jobPostRepository.save(jobPost);
    }

    private <T> T holdBeforeCommit(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            firstConnectionId = currentConnectionId();
            T result = operation.get();
            // 지연 쓰기 엔티티 변경도 잠금과 함께 DB에 반영한 뒤 멈춘다.
            entityManager.flush();
            firstWritten.countDown();
            awaitLatch(releaseFirst);
            return result;
        });
    }

    private <T> T runContender(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            contenderConnectionId.complete(currentConnectionId());
            return operation.get();
        });
    }

    private long currentConnectionId() {
        return ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue();
    }

    private void assertWaitingForJobLock(Future<?> contender, Long jobPostId) throws Exception {
        long connectionId = contenderConnectionId.get(10, TimeUnit.SECONDS);
        try (Connection observer = openLockObserverConnection();
                PreparedStatement query = observer.prepareStatement(JOB_LOCK_WAIT_QUERY)) {
            query.setLong(1, connectionId);
            query.setLong(2, firstConnectionId);
            query.setString(3, observer.getCatalog());
            query.setString(4, jobPostId.toString());
            query.setQueryTimeout(2);
            await().alias("경쟁 트랜잭션이 첫 트랜잭션의 공고 행 잠금을 기다려야 한다")
                    .pollInSameThread().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                    .until(() -> {
                        assertThat(contender.isDone()).as("잠금 관찰 전에 경쟁 요청이 끝나면 안 된다").isFalse();
                        try (ResultSet waiting = query.executeQuery()) {
                            return waiting.next();
                        }
                    });
        }
    }

    private void assertRejected(Future<?> contender, JobErrorCode expected) {
        assertThatThrownBy(() -> contender.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(BusinessException.class)
                .satisfies(failure -> assertThat(((BusinessException) failure.getCause()).getErrorCode())
                        .isEqualTo(expected));
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("경쟁 테스트 대기가 중단되었습니다.", exception);
        }
    }

    private String newKey() {
        return "funding-race-" + UUID.randomUUID();
    }
}
