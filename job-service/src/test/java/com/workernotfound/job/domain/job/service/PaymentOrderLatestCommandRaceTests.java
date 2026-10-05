package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.MutableClock;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이전 명령의 결과 기록과 새 명령 발급이 실제로 교차할 때의 최신 명령 판정.
 *
 * <p>새 명령 발급 트랜잭션이 공고 행 잠금 규칙대로 먼저 공고 행을 잠근다. 그 사이 결과 기록 트랜잭션은 공고 ID 일반 조회로
 * MySQL REPEATABLE READ 읽기 스냅샷을 만든 뒤 공고 행 잠금에서 실제로 대기한다. MySQL이 그 잠금 대기를 보고하면 발급 측이 실제 발급
 * 서비스로 다음 순번 명령을 저장·커밋하고, 결과 기록은 그 뒤에 공고 잠금을 얻는다. 이때 이미 커밋된 새 명령을 최신으로 판정해야 한다.
 * 실행 순서는 임의 sleep이 아니라 latch와 DB 잠금 대기 관찰로 제어한다.
 */
class PaymentOrderLatestCommandRaceTests extends IntegrationTestSupport {

    private static final long WAIT_SECONDS = 10;

    @Autowired
    private PaymentOrderCommandTransactionService transactionService;

    @Autowired
    private JobPaymentOrderCommandIssuer issuer;

    @Autowired
    private JobCommandService jobCommandService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobPaymentOrderCommandRepository commandRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.fixAtNow();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @Test
    void staleResultIsSupersededWhenNewerCommandWasCommittedWhileWaitingForJobLock() throws Exception {
        JobPaymentOrderCommand first = createJob();
        String leaseToken = claim(first);
        String staleOrderId = UUID.randomUUID().toString();
        CountDownLatch jobLocked = new CountDownLatch(1);
        CountDownLatch recorderWaiting = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 새 명령 발급: 공고 행을 먼저 잠그고, 결과 기록이 그 잠금에서 대기하는 것을 확인한 뒤 다음 순번을 발급·커밋한다.
            Future<JobPaymentOrderCommand> issuing = executor.submit(() -> transactionTemplate.execute(status -> {
                JobPost locked = jobPostRepository.findByIdForUpdate(first.getJobPostId()).orElseThrow();
                jobLocked.countDown();
                await(recorderWaiting);
                return issuer.issue(locked, 100_000L, LocalDateTime.now(clock));
            }));
            assertThat(jobLocked.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // 이전 명령의 결과 기록. 공고 ID 일반 조회로 읽기 스냅샷을 만든 뒤 공고 행 잠금에서 대기한다.
            Future<PaymentOrderLinkResult> recording = executor.submit(() ->
                    transactionService.recordCreated(first.getId(), leaseToken, staleOrderId, storedNow()));
            awaitJobRowLockWait(first.getJobPostId());
            recorderWaiting.countDown();

            JobPaymentOrderCommand newer = issuing.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(newer.getIssueSequence()).isEqualTo(2);
            assertThat(recording.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(PaymentOrderLinkResult.SUPERSEDED);
        } finally {
            recorderWaiting.countDown();
            executor.shutdownNow();
        }

        JobPaymentOrderCommand stale = commandRepository.findById(first.getId()).orElseThrow();
        assertThat(stale.getStatus()).isEqualTo(PaymentOrderCommandStatus.SUPERSEDED);
        assertThat(stale.getOrderId()).isNull();
        JobPost jobPost = jobPostRepository.findById(first.getJobPostId()).orElseThrow();
        assertThat(jobPost.getPaymentOrderId()).isNull();
        assertThat(jobPost.getPaymentJobVersion()).isNull();
    }

    // 결과 기록 트랜잭션이 이 공고 행의 잠금을 실제로 기다리고 있을 때까지 MySQL 잠금 대기 정보를 확인한다.
    private void awaitJobRowLockWait(Long jobPostId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        try (Connection connection = openLockObserverConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select count(*) from performance_schema.data_lock_waits w
                     join performance_schema.data_locks l on l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                     where l.OBJECT_NAME = 'job_posts' and l.LOCK_DATA = ?
                     """)) {
            statement.setString(1, String.valueOf(jobPostId));
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("결과 기록 트랜잭션이 공고 행 잠금 대기에 들어가지 않았습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("결과 기록의 잠금 대기를 확인하지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private String claim(JobPaymentOrderCommand command) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = storedNow();
        assertThat(transactionService.claim(command.getId(), token, now, now.plus(Duration.ofSeconds(30)))).isPresent();
        return token;
    }

    private JobPaymentOrderCommand createJob() {
        LocalDate workDate = LocalDate.now().plusDays(2);
        Long jobPostId = jobCommandService.create(980_001L, new CreateJobRequest(
                1L, 1L, "테스트 상점", "서울시 마포구", "최신 명령 경쟁 공고", "설명",
                workDate, LocalTime.of(9, 0), LocalTime.of(18, 0), false, 10_320, null, 1,
                new BigDecimal("37.5665000"), new BigDecimal("126.9780000"), "MEDIUM",
                LocalDateTime.of(workDate.minusDays(1), LocalTime.NOON)));
        return commandRepository.findByJobPostIdOrderByIssueSequenceAsc(jobPostId).get(0);
    }

    private LocalDateTime storedNow() {
        return JobPaymentOrderCommand.toStoredTime(LocalDateTime.now(clock));
    }
}
