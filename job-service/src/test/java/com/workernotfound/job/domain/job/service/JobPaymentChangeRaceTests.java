package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.FundingStatusNotification;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPaymentTerms;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.FundingStatusResult;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentChangeStatus;
import com.workernotfound.job.domain.job.entity.enums.RefundReviewReason;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.PaymentChangeTestSupport;
import com.workernotfound.job.support.StubPaymentServer.StubResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 결제 조건 변경·재결제 요청, 새 주문 연결, 예치 상태 수신의 경쟁.
 *
 * <p>잠금 순서 경쟁은 먼저 실행한 트랜잭션이 쓰기 후 커밋 직전에 멈춘 동안 경쟁 트랜잭션이 같은 공고 행 잠금을 실제로 기다리는지 MySQL
 * 잠금 대기로 확인한 뒤 커밋한다. 결과는 커밋 순서로만 결정되어야 하며, 양쪽 실행 순서를 모두 검증한다.
 */
class JobPaymentChangeRaceTests extends PaymentChangeTestSupport {

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
              AND (requested.LOCK_DATA = ? OR requested.LOCK_DATA IS NULL)
            """;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JobPaymentChangeCommandService changeService;

    @Autowired
    private JobFundingStatusCommandService fundingService;

    @Autowired
    private PaymentOrderCommandTransactionService transactionService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private CountDownLatch firstWritten;
    private CountDownLatch releaseFirst;
    private long firstConnectionId;
    private CompletableFuture<Long> contenderConnectionId;

    @BeforeEach
    void setUpRace() {
        executor = Executors.newFixedThreadPool(2);
        firstWritten = new CountDownLatch(1);
        releaseFirst = new CountDownLatch(1);
        contenderConnectionId = new CompletableFuture<>();
    }

    @AfterEach
    void tearDownRace() throws InterruptedException {
        releaseFirst.countDown();
        executor.shutdown();
        try {
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyRequestsCreateOneChangeAndCommand() throws Exception {
        LinkedJob job = createLinkedJob(1);
        String key = newKey("same");
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcResult> request = () -> {
            start.await();
            return changeTerms(job.jobPostId(), job.ownerMemberId(), key, terms(job, 2)).andReturn();
        };

        Future<MvcResult> first = executor.submit(request);
        Future<MvcResult> second = executor.submit(request);
        start.countDown();

        for (Future<MvcResult> result : List.of(first, second)) {
            assertThat(result.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(changeIdOf(first.get())).isEqualTo(changeIdOf(second.get()));
        assertThat(commandRepository.findByJobPostIdOrderByIssueSequenceAsc(job.jobPostId())).hasSize(2);
    }

    @Test
    void concurrentChangeAndRepaymentAllowOnlyOneReplacement() throws Exception {
        LinkedJob job = createLinkedJob(1);
        CountDownLatch start = new CountDownLatch(1);

        Future<MvcResult> change = executor.submit(() -> {
            start.await();
            return changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 2)).andReturn();
        });
        Future<MvcResult> retry = executor.submit(() -> {
            start.await();
            return retryPayment(job.jobPostId(), job.ownerMemberId(), newKey("r")).andReturn();
        });
        start.countDown();

        List<Integer> statuses = List.of(change.get(10, TimeUnit.SECONDS).getResponse().getStatus(),
                retry.get(10, TimeUnit.SECONDS).getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        MvcResult loser = statuses.get(0) == 409 ? change.get() : retry.get();
        assertThat(loser.getResponse().getContentAsString()).contains("JOB-409-015");
        assertThat(commandRepository.findByJobPostIdOrderByIssueSequenceAsc(job.jobPostId())).hasSize(2);
    }

    // 조건 변경이 먼저 커밋되면, 기다리던 이전 주문의 예치 확인은 이전 조건으로 공개한다. 변경은 payment-service의 거절로 끝난다.
    @Test
    void earlierOrderFundingWaitingBehindChangePublishesCurrentTerms() throws Exception {
        LinkedJob job = createLinkedJob(1);
        JobPaymentTerms terms = jobPost(job.jobPostId()).paymentTerms();
        JobPaymentTerms changed = withRecruitCount(terms, 2);

        JobPaymentChange change = firstThen(job,
                () -> changeService.changeTerms(job.jobPostId(), job.ownerMemberId(), changed, newKey("c")),
                () -> receive(job, true));

        assertThat(contenderResult).isEqualTo(FundingStatusResult.PUBLISHED);
        assertThat(changeRequest(change.request().getId()).getStatus()).isEqualTo(PaymentChangeStatus.PENDING);
        assertThat(jobPost(job.jobPostId()).paymentTerms()).isEqualTo(terms);
        PAYMENT_SERVER.enqueue(StubResponse.error(409, "ORDER-409-001"));
        paymentOrderDispatcher.dispatch(change.command().getId());
        assertThat(changeRequest(change.request().getId()).getStatus()).isEqualTo(PaymentChangeStatus.REJECTED);
        assertThat(jobPost(job.jobPostId()).getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(jobPost(job.jobPostId()).paymentTerms()).isEqualTo(terms);
    }

    // 예치 확인이 먼저 커밋되면, 기다리던 조건 변경은 공개된 공고라 거절된다.
    @Test
    void changeWaitingBehindEarlierOrderFundingIsRejected() throws Exception {
        LinkedJob job = createLinkedJob(1);
        JobPaymentTerms changed = withRecruitCount(jobPost(job.jobPostId()).paymentTerms(), 2);

        firstThen(job, () -> receive(job, true),
                () -> changeService.changeTerms(job.jobPostId(), job.ownerMemberId(), changed, newKey("c")));

        assertThat(contenderFailure).isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.getErrorCode()).isEqualTo(JobErrorCode.PAYMENT_CHANGE_NOT_ALLOWED));
        assertThat(commandRepository.findByJobPostIdOrderByIssueSequenceAsc(job.jobPostId())).hasSize(1);
    }

    // 새 주문 연결이 먼저 커밋되면, 기다리던 새 주문의 예치 확인은 연결 대기(409)가 아니라 새 조건으로 공개한다.
    @Test
    void newOrderFundingWaitingBehindLinkPublishesNewTerms() throws Exception {
        LinkedJob job = createLinkedJob(1);
        JobPaymentChange change = changeService.changeTerms(job.jobPostId(), job.ownerMemberId(),
                withRecruitCount(jobPost(job.jobPostId()).paymentTerms(), 2), newKey("c"));
        String token = claim(change.command());
        String newOrderId = UUID.randomUUID().toString();
        LinkedJob created = new LinkedJob(job.jobPostId(), newOrderId, change.command().getJobVersion(),
                job.ownerMemberId(), change.command().getAmount());

        firstThen(job, () -> transactionService.recordCreated(change.command().getId(), token, newOrderId, now()),
                () -> receive(created, true));

        assertThat(contenderResult).isEqualTo(FundingStatusResult.PUBLISHED);
        JobPost published = jobPost(job.jobPostId());
        assertThat(published.getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(published.getRecruitCount()).isEqualTo(2);
        assertThat(published.getPaymentOrderId()).isEqualTo(newOrderId);
    }

    // 이전 주문의 예치 반영이 먼저 커밋되면 새 주문은 연결하지 않는다. 연결 트랜잭션은 잠금 대기 전에 읽기 스냅샷을 만들었으므로
    // 공개되지 않은(PUBLICATION_SKIPPED) 예치 반영은 잠금 조회로만 보인다.
    @Test
    void linkWaitingBehindEarlierOrderFundingDoesNotReplaceFundedOrder() throws Exception {
        LinkedJob job = createLinkedJob(1);
        JobPost jobPost = jobPost(job.jobPostId());
        JobPaymentTerms changed = new JobPaymentTerms(jobPost.getWorkDate(), jobPost.getStartTime(), jobPost.getEndTime(),
                false, jobPost.getBaseHourlyWage(), null, 2, jobPost.getWorkDate().atTime(LocalTime.of(8, 0)));
        JobPaymentChange change = changeService.changeTerms(job.jobPostId(), job.ownerMemberId(), changed, newKey("c"));
        clock.fixAt(jobPost.getApplicationDeadline().atZone(ZoneId.systemDefault()).toInstant());
        String token = claim(change.command());
        String newOrderId = UUID.randomUUID().toString();

        firstThen(job, () -> receive(job, true),
                () -> transactionService.recordCreated(change.command().getId(), token, newOrderId, now()));

        assertThat(contenderResult).isEqualTo(PaymentOrderLinkResult.JOB_STATE_CHANGED);
        JobPost unchanged = jobPost(job.jobPostId());
        assertThat(unchanged.getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        assertThat(unchanged.getPaymentOrderId()).isEqualTo(job.orderId());
        assertThat(unchanged.getRecruitCount()).isEqualTo(1);
        assertThat(changeRequest(change.request().getId()).getStatus()).isEqualTo(PaymentChangeStatus.REJECTED);
        assertThat(refundReviewRepository.findByOrderId(job.orderId())).hasValueSatisfying(review ->
                assertThat(review.getReason()).isEqualTo(RefundReviewReason.APPLICATION_DEADLINE_PASSED));
    }

    // 새 주문 연결이 먼저 커밋되면, 기다리던 이전 주문의 예치 확인은 이전 주문 알림으로 기록만 하고 새 주문을 공개하지 않는다.
    @Test
    void earlierOrderFundingWaitingBehindLinkIsStaleAndUnderReview() throws Exception {
        LinkedJob job = createLinkedJob(1);
        JobPaymentChange change = changeService.changeTerms(job.jobPostId(), job.ownerMemberId(),
                withRecruitCount(jobPost(job.jobPostId()).paymentTerms(), 2), newKey("c"));
        String token = claim(change.command());
        String newOrderId = UUID.randomUUID().toString();

        firstThen(job, () -> transactionService.recordCreated(change.command().getId(), token, newOrderId, now()),
                () -> receive(job, true));

        assertThat(contenderResult).isEqualTo(FundingStatusResult.STALE_ORDER);
        JobPost linked = jobPost(job.jobPostId());
        assertThat(linked.getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        assertThat(linked.getPaymentOrderId()).isEqualTo(newOrderId);
        assertThat(linked.getRecruitCount()).isEqualTo(2);
        assertThat(refundReviewRepository.findByOrderId(job.orderId())).isPresent();
    }

    private Object contenderResult;
    private Throwable contenderFailure;

    // 첫 작업을 쓰고 커밋 직전에 멈춘 뒤, 경쟁 작업이 같은 공고 행 잠금을 기다리는지 확인하고 커밋한다.
    private <T> T firstThen(LinkedJob job, Supplier<T> first, Supplier<?> contender) throws Exception {
        Future<T> held = executor.submit(() -> holdBeforeCommit(first));
        awaitLatch(firstWritten);
        Future<?> contending = executor.submit(() -> runContender(contender));
        assertWaitingForJobLock(contending, job.jobPostId());
        releaseFirst.countDown();
        T result = held.get(10, TimeUnit.SECONDS);
        try {
            contenderResult = contending.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            contenderFailure = failure.getCause();
        }
        return result;
    }

    private FundingStatusResult receive(LinkedJob job, boolean funded) {
        FundingStatusNotification notification = new FundingStatusNotification(
                job.orderId(), job.paymentJobVersion(), job.ownerMemberId(), job.amount(), "KRW", 1L, funded);
        return fundingService.receive(job.jobPostId(), notification, newKey("funding")).getResult();
    }

    private String claim(JobPaymentOrderCommand command) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = now();
        assertThat(transactionService.claim(command.getId(), token, now, now.plusSeconds(30))).isPresent();
        return token;
    }

    private JobPaymentTerms withRecruitCount(JobPaymentTerms terms, int recruitCount) {
        return new JobPaymentTerms(terms.workDate(), terms.startTime(), terms.endTime(), terms.endTimeNextDay(),
                terms.baseHourlyWage(), terms.extraWage(), recruitCount, terms.applicationDeadline());
    }

    private Long changeIdOf(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(content, "$.data.changeId")).longValue();
    }

    private <T> T holdBeforeCommit(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            firstConnectionId = currentConnectionId();
            T result = operation.get();
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

    private void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("경쟁 테스트 대기가 중단되었습니다.", exception);
        }
    }
}
