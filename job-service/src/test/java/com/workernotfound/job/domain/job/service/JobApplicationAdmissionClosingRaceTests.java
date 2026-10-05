package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.UrgencyLevel;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class JobApplicationAdmissionClosingRaceTests extends IntegrationTestSupport {

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

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JobApplicationAdmissionCommandService admissionService;

    @Autowired
    private JobApplicationAdmissionRepository admissionRepository;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private CountDownLatch firstLocked;
    private CountDownLatch releaseFirst;
    private long firstConnectionId;
    private CompletableFuture<Long> contenderConnectionId;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        firstLocked = new CountDownLatch(1);
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
    void rejectsAdmissionAfterConcurrentClosingCommitsFirst() throws Exception {
        JobPost jobPost = saveJobPost();
        String key = "closing-first-" + UUID.randomUUID();
        Future<?> closing = executor.submit(() -> holdBeforeCommit(() -> {
            closeJob(jobPost.getId());
            return null;
        }));
        awaitLatch(firstLocked);
        Future<JobApplicationAdmission> admission = executor.submit(() -> runContender(() ->
                admissionService.create(jobPost.getId(), 100L, key)));
        assertWaitingForJobLock(admission, jobPost.getId());

        releaseFirst.countDown();
        closing.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> admission.get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(BusinessException.class)
                .satisfies(failure -> assertThat(((BusinessException) failure.getCause()).getErrorCode())
                        .isEqualTo(JobErrorCode.JOB_NOT_OPEN));
        assertThat(admissionRepository.findByIdempotencyKey(key)).isEmpty();
        assertThat(jobPostRepository.findById(jobPost.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.CLOSED);
    }

    @Test
    void preservesAdmissionWhenConcurrentClosingWaitsForAdmissionCommit() throws Exception {
        JobPost jobPost = saveJobPost();
        String key = "admission-first-" + UUID.randomUUID();
        Future<JobApplicationAdmission> admission = executor.submit(() -> holdBeforeCommit(() ->
                admissionService.create(jobPost.getId(), 100L, key)));
        awaitLatch(firstLocked);
        Future<?> closing = executor.submit(() -> runContender(() -> {
            closeJob(jobPost.getId());
            return null;
        }));
        assertWaitingForJobLock(closing, jobPost.getId());

        releaseFirst.countDown();
        JobApplicationAdmission issued = admission.get(10, TimeUnit.SECONDS);
        closing.get(10, TimeUnit.SECONDS);
        assertThat(issued.getJobVersion()).isEqualTo(jobPost.getVersion());
        assertThat(admissionRepository.findByIdempotencyKey(key).orElseThrow().getId())
                .isEqualTo(issued.getId());
        JobPost closed = jobPostRepository.findById(jobPost.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(closed.getVersion()).isEqualTo(jobPost.getVersion() + 1);
        assertThat(admissionService.create(jobPost.getId(), 100L, key).getId()).isEqualTo(issued.getId());
        assertThatThrownBy(() -> admissionService.create(jobPost.getId(), 100L, key + "-new"))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(JobErrorCode.JOB_NOT_OPEN));
    }

    // 운영 서비스의 REQUIRED 트랜잭션을 참여시켜, 실제 쓰기 후 커밋 전까지 행 잠금을 유지한다.
    private <T> T holdBeforeCommit(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            firstConnectionId = currentConnectionId();
            T result = operation.get();
            firstLocked.countDown();
            try {
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("경쟁 테스트 트랜잭션 대기가 중단되었습니다.", exception);
            }
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
        assertThatThrownBy(() -> contender.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private void awaitLatch(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }

    // 마감 API는 아직 없으므로, 동일 공고 행을 잠그는 별도 트랜잭션으로 마감 쓰기를 재현한다.
    private void closeJob(Long jobPostId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            JobPost jobPost = jobPostRepository.findByIdForUpdate(jobPostId).orElseThrow();
            ReflectionTestUtils.setField(jobPost, "status", JobStatus.CLOSED);
            jobPostRepository.flush();
        });
    }

    private JobPost saveJobPost() {
        return jobPostRepository.save(JobPostFixture.open(JobPost.builder()
                .businessId(1L)
                .ownerId(7L)
                .categoryId(1L)
                .storeName("테스트 상점")
                .address("서울시 마포구")
                .title("마감 경쟁 테스트 공고")
                .description("테스트 설명")
                .workDate(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .endTimeNextDay(false)
                .baseHourlyWage(10_000)
                .recruitCount(1)
                .latitude(new BigDecimal("37.5665000"))
                .longitude(new BigDecimal("126.9780000"))
                .urgencyLevel(UrgencyLevel.MEDIUM)
                .applicationDeadline(LocalDateTime.now().plusHours(1))
                .build()));
    }
}
