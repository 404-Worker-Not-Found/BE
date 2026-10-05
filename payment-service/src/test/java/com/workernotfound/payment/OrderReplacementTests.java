package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.workernotfound.payment.global.account.AccountGateService;
import org.springframework.transaction.annotation.Transactional;
import com.workernotfound.payment.domain.order.dto.*;
import com.workernotfound.payment.domain.order.exception.OrderErrorCode;
import com.workernotfound.payment.domain.order.service.*;
import com.workernotfound.payment.external.job.FundingJobClient;
import com.workernotfound.payment.external.toss.*;
import com.workernotfound.payment.global.exception.BusinessException;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * job-service의 결제 조건 변경·재결제가 기대는 주문 교체 계약. 교체 가능 여부는 이 서비스가 자신의 잠금 안에서 최종 판단한다.
 *
 * <p>결제 키가 연결되지 않은 READY와 결과가 확인된 FAILED만 교체한다. 같은 결제용 버전은 같은 금액의 FAILED 재결제에만 허용한다.
 * CONFIRMING·DEPOSITED·REVIEW_REQUIRED와 낮은 버전은 409이며, 거절한 요청은 롤백되어 주문도 명령 키도 남지 않는다.
 */
class OrderReplacementTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(500000);
  static final String ACCOUNT_OR_ORDER_LOCK_WAIT_QUERY =
      """
      SELECT 1
      FROM performance_schema.data_lock_waits waits
      JOIN performance_schema.data_locks requested
        ON requested.ENGINE = waits.ENGINE
       AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
      JOIN performance_schema.threads requester ON requester.THREAD_ID = waits.REQUESTING_THREAD_ID
      JOIN performance_schema.threads blocker ON blocker.THREAD_ID = waits.BLOCKING_THREAD_ID
      WHERE requester.PROCESSLIST_ID = ? AND blocker.PROCESSLIST_ID = ?
        AND requested.OBJECT_NAME IN ('payment_orders', 'account_gates') AND requested.LOCK_STATUS = 'WAITING'
      """;

  @Autowired OrderApplicationService service;
  @Autowired OrderTransactionService transactions;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @MockitoBean TossGateway toss;
  @MockitoBean FundingJobClient job;

  ExecutorService executor;
  CountDownLatch firstWritten;
  CountDownLatch releaseFirst;
  volatile long firstConnectionId;
  CompletableFuture<Long> contenderConnectionId;

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
    assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void replacesReadyOrderOnlyWithHigherVersionAndBlocksOldApproval() {
    long jobId = IDS.incrementAndGet();
    var ready = service.create(key(), request(jobId, 1, "10000"));

    assertConflictWithoutTrace(jobId, request(jobId, 1, "10000"));
    assertConflictWithoutTrace(jobId, request(jobId, 1, "20000"));
    var replacement = service.create(key(), request(jobId, 2, "20000"));

    assertThat(status(ready.orderId())).isEqualTo("SUPERSEDED");
    assertThat(replacement.status()).isEqualTo("READY");
    assertThat(activeOrder(jobId)).isEqualTo(replacement.orderId());
    assertThatThrownBy(() -> service.confirm(ready.orderId(), 20L, confirm("key-a", "10000")))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(toss);
    // 낮은 버전은 교체 요청이 아니다.
    assertConflictWithoutTrace(jobId, request(jobId, 1, "10000"));
  }

  @Test
  void repaysFailedOrderWithSameVersionOnlyForSameAmount() {
    long jobId = IDS.incrementAndGet();
    var failed = service.create(key(), request(jobId, 1, "10000"));
    when(toss.confirm(failed.orderId(), "key-f", failed.amount()))
        .thenReturn(payment(failed, "key-f", "ABORTED"));
    assertThat(service.confirm(failed.orderId(), 20L, confirm("key-f", "10000")).status())
        .isEqualTo("FAILED");

    assertConflictWithoutTrace(jobId, request(jobId, 1, "12000"));
    String retryKey = key();
    var retry = service.create(retryKey, request(jobId, 1, "10000"));

    assertThat(retry.orderId()).isNotEqualTo(failed.orderId());
    assertThat(retry.jobVersion()).isEqualTo(1L);
    assertThat(status(failed.orderId())).isEqualTo("SUPERSEDED");
    // 같은 키의 재요청은 새 주문을 만들지 않고 처음 만든 주문을 돌려준다.
    assertThat(service.create(retryKey, request(jobId, 1, "10000")).orderId())
        .isEqualTo(retry.orderId());
    assertThat(orderCount(jobId)).isEqualTo(2);
  }

  @Test
  void refusesToReplaceConfirmingDepositedOrReviewRequiredOrders() {
    long confirmingJob = IDS.incrementAndGet();
    var confirming = service.create(key(), request(confirmingJob, 1, "10000"));
    transactions.prepare(confirming.orderId(), 20L, confirm("key-c", "10000"));
    assertThat(status(confirming.orderId())).isEqualTo("CONFIRMING");

    long depositedJob = IDS.incrementAndGet();
    var deposited = service.create(key(), request(depositedJob, 1, "10000"));
    when(toss.confirm(deposited.orderId(), "key-d", deposited.amount()))
        .thenReturn(payment(deposited, "key-d", "DONE"));
    assertThat(service.confirm(deposited.orderId(), 20L, confirm("key-d", "10000")).status())
        .isEqualTo("DEPOSITED");

    long reviewJob = IDS.incrementAndGet();
    var review = service.create(key(), request(reviewJob, 1, "10000"));
    when(toss.confirm(review.orderId(), "key-r", review.amount()))
        .thenReturn(
            new TossPayment(
                "key-r",
                review.orderId(),
                "DONE",
                "KRW",
                "가상계좌",
                review.amount(),
                review.amount(),
                OffsetDateTime.now()));
    assertThat(service.confirm(review.orderId(), 20L, confirm("key-r", "10000")).status())
        .isEqualTo("REVIEW_REQUIRED");

    for (var order : List.of(confirming, deposited, review)) {
      String before = status(order.orderId());
      assertConflictWithoutTrace(order.jobPostId(), request(order.jobPostId(), 2, "20000"));
      assertConflictWithoutTrace(order.jobPostId(), request(order.jobPostId(), 1, "10000"));
      assertThat(status(order.orderId())).isEqualTo(before);
      assertThat(activeOrder(order.jobPostId())).isEqualTo(order.orderId());
    }
  }

  @Test
  void replayOfSupersededOrderCommandReportsSupersededStatus() {
    long jobId = IDS.incrementAndGet();
    String firstKey = key();
    var first = service.create(firstKey, request(jobId, 1, "10000"));
    service.create(key(), request(jobId, 2, "10000"));

    // job-service는 이 상태를 연결하지 않는다(ORDER_SUPERSEDED).
    var replay = service.create(firstKey, request(jobId, 1, "10000"));
    assertThat(replay.orderId()).isEqualTo(first.orderId());
    assertThat(replay.status()).isEqualTo("SUPERSEDED");
  }

  // 결제 승인 준비가 먼저 주문 행을 잠그면, 기다리던 교체 요청은 CONFIRMING을 보고 거절된다.
  @Test
  void replacementWaitingBehindApprovalIsRejected() throws Exception {
    long jobId = IDS.incrementAndGet();
    var order = service.create(key(), request(jobId, 1, "10000"));
    String replacementKey = key();

    Future<?> approval =
        executor.submit(
            () -> holdBeforeCommit(() -> transactions.prepare(order.orderId(), 20L, confirm("key-p", "10000"))));
    awaitLatch(firstWritten);
    Future<OrderResponse> replacement =
        executor.submit(() -> runContender(() -> service.create(replacementKey, request(jobId, 2, "20000"))));
    assertWaitingForAccountOrOrderLock(replacement);
    releaseFirst.countDown();
    approval.get(10, TimeUnit.SECONDS);

    assertThatThrownBy(() -> replacement.get(10, TimeUnit.SECONDS))
        .hasCauseInstanceOf(BusinessException.class);
    assertThat(status(order.orderId())).isEqualTo("CONFIRMING");
    assertThat(activeOrder(jobId)).isEqualTo(order.orderId());
    assertThat(commandCount(replacementKey)).isZero();
  }

  // 교체가 먼저 이전 주문을 SUPERSEDED로 바꾸면, 기다리던 승인 준비는 거절되고 PG 승인을 요청하지 않는다.
  @Test
  void approvalWaitingBehindReplacementIsRejectedWithoutProviderCall() throws Exception {
    long jobId = IDS.incrementAndGet();
    var order = service.create(key(), request(jobId, 1, "10000"));

    Future<OrderResponse> replacement =
        executor.submit(() -> holdBeforeCommit(() -> service.create(key(), request(jobId, 2, "20000"))));
    awaitLatch(firstWritten);
    Future<?> approval =
        executor.submit(
            () -> runContender(() -> transactions.prepare(order.orderId(), 20L, confirm("key-q", "10000"))));
    assertWaitingForAccountOrOrderLock(approval);
    releaseFirst.countDown();
    var replaced = replacement.get(10, TimeUnit.SECONDS);

    assertThatThrownBy(() -> approval.get(10, TimeUnit.SECONDS))
        .hasCauseInstanceOf(BusinessException.class);
    assertThat(status(order.orderId())).isEqualTo("SUPERSEDED");
    assertThat(activeOrder(jobId)).isEqualTo(replaced.orderId());
    assertThat(jdbc.queryForObject("SELECT payment_key FROM payment_orders WHERE id=?", String.class, order.orderId()))
        .isNull();
    verify(toss, never()).confirm(any(), any(), any());
  }

  void assertConflictWithoutTrace(long jobId, CreateOrderRequest request) {
    long ordersBefore = orderCount(jobId);
    String key = key();
    assertThatThrownBy(() -> service.create(key, request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(OrderErrorCode.CONFLICT));
    assertThat(orderCount(jobId)).isEqualTo(ordersBefore);
    // 거절한 요청은 롤백되어 키를 선점하지 않는다. job-service는 거절된 키를 다시 보내지 않는다.
    assertThat(commandCount(key)).isZero();
  }

  <T> T holdBeforeCommit(Supplier<T> operation) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              firstConnectionId = connectionId();
              T result = operation.get();
              firstWritten.countDown();
              awaitLatch(releaseFirst);
              return result;
            });
  }

  <T> T runContender(Supplier<T> operation) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              contenderConnectionId.complete(connectionId());
              return operation.get();
            });
  }

  void assertWaitingForAccountOrOrderLock(Future<?> contender) throws Exception {
    long contenderId = contenderConnectionId.get(10, TimeUnit.SECONDS);
    try (Connection observer = openRootConnection();
        PreparedStatement query = observer.prepareStatement(ACCOUNT_OR_ORDER_LOCK_WAIT_QUERY)) {
      query.setLong(1, contenderId);
      query.setLong(2, firstConnectionId);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        assertThat(contender.isDone()).as("잠금 관찰 전에 경쟁 요청이 끝나면 안 된다").isFalse();
        try (ResultSet waiting = query.executeQuery()) {
          if (waiting.next()) return;
        }
        Thread.sleep(50);
      }
    }
    throw new AssertionError("경쟁 트랜잭션이 계정 또는 주문 행 잠금을 기다리지 않았습니다.");
  }

  void awaitLatch(CountDownLatch latch) {
    try {
      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  long connectionId() {
    return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  CreateOrderRequest request(long jobId, long version, String amount) {
    return new CreateOrderRequest(jobId, version, 20L, new BigDecimal(amount), "KRW");
  }

  ConfirmOrderRequest confirm(String paymentKey, String amount) {
    return new ConfirmOrderRequest(paymentKey, new BigDecimal(amount));
  }

  TossPayment payment(OrderResponse order, String key, String status) {
    return new TossPayment(
        key, order.orderId(), status, "KRW", "카드", order.amount(), order.amount(), OffsetDateTime.now());
  }

  String status(String orderId) {
    return jdbc.queryForObject("SELECT status FROM payment_orders WHERE id=?", String.class, orderId);
  }

  String activeOrder(long jobId) {
    return jdbc.queryForObject(
        "SELECT active_order_id FROM payment_order_jobs WHERE job_post_id=?", String.class, jobId);
  }

  long orderCount(long jobId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_orders WHERE job_post_id=?", Long.class, jobId);
  }

  long commandCount(String key) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_order_commands WHERE command_key=?", Long.class, key);
  }
}

class AccountGateTests extends IntegrationTestSupport {
    @Autowired AccountGateService gates;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Test
    @Transactional
    void ongoingRecordsRejectWithdrawalWithoutInstallingABarrier() {
        jdbc.update("insert into payment_deposits(job_post_id,owner_member_id,currency,deposited_amount,locked_amount,version) values(919205,919203,'KRW',10000,0,0)");
        String key=UUID.randomUUID().toString();
        assertThat(gates.transition(919203L,key,"prepare").state()).isEqualTo("REJECTED");
        assertThat(gates.isActive(919203L)).isTrue();
        assertThat(gates.transition(919203L,key,"prepare").state()).isEqualTo("REJECTED");
    }
    @Test
    void releasedAttemptCannotUndoLaterCommittedWithdrawal() {
        long member = 919100L;
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        assertThat(gates.transition(member,first,"prepare").state()).isEqualTo("PREPARED");
        assertThat(gates.transition(member,first,"release").state()).isEqualTo("RELEASED");
        assertThat(gates.transition(member,second,"prepare").state()).isEqualTo("PREPARED");
        assertThat(gates.transition(member,second,"commit").state()).isEqualTo("COMMITTED");
        assertThat(gates.transition(member,first,"release").state()).isEqualTo("RELEASED");
        assertThat(gates.isActive(member)).isFalse();
        assertThat(gates.transition(member,second,"commit").state()).isEqualTo("COMMITTED");
    }
    @Test
    void prepareWaitsForInFlightCreationAndRejectsSubsequentCreation() throws Exception {
        long member = 919101L;
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        var transactions = new TransactionTemplate(transactionManager);
        try {
            Future<?> creation = executor.submit(() -> transactions.executeWithoutResult(status -> {
                gates.requireActive(member); locked.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            Future<AccountGateService.Result> withdrawal = executor.submit(() -> gates.transition(member,UUID.randomUUID().toString(),"prepare"));
            assertThatThrownBy(() -> withdrawal.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); creation.get(5,TimeUnit.SECONDS);
            assertThat(withdrawal.get(5,TimeUnit.SECONDS).state()).isEqualTo("PREPARED");
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> gates.requireActive(member)))
                .isInstanceOf(BusinessException.class);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
