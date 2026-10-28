package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.workernotfound.payment.domain.order.service.OrderApplicationService;
import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.refund.dto.request.RefundRequest;
import com.workernotfound.payment.domain.refund.dto.response.RefundResponse;
import com.workernotfound.payment.domain.refund.service.*;
import com.workernotfound.payment.external.job.FundingJobClient;
import com.workernotfound.payment.external.toss.*;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class RefundExecutionIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(800_000);
  static final String REASON = "모집 종료 후 미사용 예치금";
  @Autowired RefundApplicationService refunds;
  @Autowired RefundExecutionService executions;
  @Autowired RefundExecutionTransactionService transactions;
  @Autowired OrderApplicationService orders;
  @Autowired PaymentDepositRepository deposits;
  @Autowired JdbcTemplate jdbc;
  @Autowired ApplicationContext context;
  @MockitoBean TossGateway toss;
  @MockitoBean FundingJobClient job;

  // 검증된 승인으로 만든 1만 원 예치와 DEPOSITED 주문을 흉내 낸다.
  long deposit() {
    long job = IDS.incrementAndGet();
    deposits.saveAndFlush(PaymentDeposit.builder().jobPostId(job).ownerMemberId(20L)
        .currency("KRW").depositedAmount(new BigDecimal("10000")).build());
    jdbc.update("INSERT INTO payment_orders(id,job_post_id,job_version,owner_member_id,amount,currency,status,"
        + "payment_key,approved_at) VALUES (?,?,1,20,10000,'KRW','DEPOSITED',?,CURRENT_TIMESTAMP(6))",
        orderId(job), job, paymentKey(job));
    return job;
  }

  String orderId(long job) { return "refund-order-" + job; }

  String paymentKey(long job) { return "refund-pk-" + job; }

  long refund(long job, String amount) {
    RefundResponse refund = refunds.request(UUID.randomUUID().toString(),
        new RefundRequest(job, 20L, new BigDecimal(amount), "KRW", REASON));
    return Long.parseLong(refund.refundId());
  }

  TossPayment payment(long job, String balance, TossPayment.Cancel... cancels) {
    String status = "10000".equals(balance) ? "DONE" : "0".equals(balance) ? "CANCELED" : "PARTIAL_CANCELED";
    return new TossPayment(paymentKey(job), orderId(job), status, "KRW", "카드", new BigDecimal("10000"),
        new BigDecimal(balance), OffsetDateTime.now(), List.of(cancels));
  }

  TossPayment.Cancel cancel(String transactionKey, String amount) {
    return new TossPayment.Cancel(transactionKey, new BigDecimal(amount), "DONE", OffsetDateTime.now());
  }

  void cancelReturns(long job, TossPayment payment) {
    when(toss.cancel(eq(paymentKey(job)), any(), eq(REASON), anyString())).thenReturn(payment);
  }

  void cancelFails(long job, Integer status, String code) {
    when(toss.cancel(eq(paymentKey(job)), any(), eq(REASON), anyString()))
        .thenThrow(new TossClientException(status, code, new RuntimeException("provider")));
  }

  RefundResponse read(long refundId) { return refunds.getRefund(refundId); }

  BigDecimal balance(long job, String column) {
    return jdbc.queryForObject("SELECT " + column + " FROM payment_deposits WHERE job_post_id = ?",
        BigDecimal.class, job);
  }

  boolean blocked(long job) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT funding_blocked FROM payment_deposits WHERE job_post_id = ?", Boolean.class, job));
  }

  String orderStatus(long job) {
    return jdbc.queryForObject("SELECT status FROM payment_orders WHERE id = ?", String.class, orderId(job));
  }

  void makeDue(long refundId) {
    jdbc.update("UPDATE payment_refunds SET next_attempt_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)"
        + " WHERE id = ?", refundId);
  }

  void requery(long job, TossPayment payment) {
    when(toss.getPayment(paymentKey(job))).thenReturn(payment);
    jdbc.update("UPDATE payment_orders SET next_check_at = CURRENT_TIMESTAMP(6) WHERE id = ?", orderId(job));
    orders.recover(orderId(job));
  }

  @Test
  void successfulCancelCompletesRefundAndOwnRefundDoesNotBlockDeposit() {
    long job = deposit();
    long refundId = refund(job, "3000");
    cancelReturns(job, payment(job, "7000", cancel("tx-1", "3000")));
    executions.execute(refundId);

    var captured = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
    verify(toss).cancel(eq(paymentKey(job)), captured.capture(), eq(REASON), eq("refund-" + refundId));
    assertThat(captured.getValue()).isEqualByComparingTo("3000");
    RefundResponse refund = read(refundId);
    assertThat(refund.status()).isEqualTo("SUCCEEDED");
    assertThat(refund.completedAt()).isNotNull();
    assertThat(jdbc.queryForObject("SELECT provider_transaction_key FROM payment_refunds WHERE id = ?",
        String.class, refundId)).isEqualTo("tx-1");
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("0");
    assertThat(balance(job, "refunded_amount")).isEqualByComparingTo("3000");
    assertThat(balance(job, "deposited_amount")).isEqualByComparingTo("10000");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund_histories WHERE refund_id = ? AND"
        + " previous_status = 'REQUESTED' AND next_status = 'SUCCEEDED'", Long.class, refundId)).isEqualTo(1);

    // 환불 뒤 웹훅·재조회가 PARTIAL_CANCELED를 보더라도 이쪽 환불로 설명되므로 차단하지 않는다.
    requery(job, payment(job, "7000", cancel("tx-1", "3000")));
    assertThat(orderStatus(job)).isEqualTo("DEPOSITED");
    assertThat(blocked(job)).isFalse();
    // 장부에 없는 추가 취소는 외부 취소로 보고 기존처럼 차단한다.
    requery(job, payment(job, "6000", cancel("tx-1", "3000"), cancel("tx-x", "1000")));
    assertThat(orderStatus(job)).isEqualTo("REVIEW_REQUIRED");
    assertThat(blocked(job)).isTrue();
  }

  @Test
  void lostResponseIsConfirmedByQueryWithoutSecondCancel() {
    long job = deposit();
    long refundId = refund(job, "3000");
    cancelFails(job, null, null);
    executions.execute(refundId);
    assertThat(read(refundId).status()).isEqualTo("REQUESTED");
    assertThat(jdbc.queryForObject("SELECT attempts FROM payment_refunds WHERE id = ?", Integer.class, refundId))
        .isEqualTo(1);
    // 아직 재시도 시각 전이면 다시 점유하지 않는다.
    executions.execute(refundId);
    verify(toss, times(1)).cancel(any(), any(), any(), any());

    // PG에는 반영됐지만 저장 전인 환불: 재조회가 진행 중 환불로 설명하고 차단하지 않는다.
    requery(job, payment(job, "7000", cancel("tx-1", "3000")));
    assertThat(blocked(job)).isFalse();

    makeDue(refundId);
    executions.execute(refundId);
    assertThat(read(refundId).status()).isEqualTo("SUCCEEDED");
    assertThat(balance(job, "refunded_amount")).isEqualByComparingTo("3000");
    verify(toss, times(1)).cancel(any(), any(), any(), any());
  }

  @ParameterizedTest
  @CsvSource({"0,2000", "1000,2000", "1000,9000"})
  void delayedRequeryRetriesWhenRefundLedgerChanges(String previous, String next) throws Exception {
    long job = deposit();
    BigDecimal previousAmount = new BigDecimal(previous);
    if (previousAmount.signum() > 0) {
      long first = refund(job, previous);
      cancelReturns(job, payment(job, new BigDecimal("10000").subtract(previousAmount).toPlainString()));
      executions.execute(first);
    }
    String oldBalance = new BigDecimal("10000").subtract(previousAmount).toPlainString();
    long second = refund(job, next);
    var queried = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    when(toss.getPayment(paymentKey(job))).thenAnswer(invocation -> {
      queried.countDown();
      if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("조회 재개 시간 초과");
      return payment(job, oldBalance);
    });
    var executor = Executors.newSingleThreadExecutor();
    var recovery = executor.submit(() -> orders.recover(orderId(job)));
    String latestBalance = new BigDecimal(oldBalance).subtract(new BigDecimal(next)).toPlainString();
    try {
      assertThat(queried.await(10, TimeUnit.SECONDS)).isTrue();
      cancelReturns(job, payment(job, latestBalance));
      executions.execute(second);
      assertThat(read(second).status()).isEqualTo("SUCCEEDED");
    } finally {
      resume.countDown();
      executor.shutdown();
    }
    recovery.get(10, TimeUnit.SECONDS);

    assertThat(orderStatus(job)).isEqualTo("DEPOSITED");
    assertThat(blocked(job)).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_funding_notifications WHERE order_id = ?"
        + " AND funded = FALSE", Long.class, orderId(job))).isZero();
    assertThat(jdbc.queryForObject("SELECT next_check_at IS NOT NULL AND lease_token IS NULL"
        + " FROM payment_orders WHERE id = ?", Boolean.class, orderId(job))).isTrue();
    requery(job, payment(job, latestBalance));
    assertThat(orderStatus(job)).isEqualTo("DEPOSITED");
    assertThat(blocked(job)).isFalse();
    assertThat(jdbc.queryForObject("SELECT next_check_at FROM payment_orders WHERE id = ?",
        java.time.LocalDateTime.class, orderId(job))).isNull();
    if (new BigDecimal(latestBalance).signum() > 0) {
      requery(job, payment(job, new BigDecimal(latestBalance).subtract(BigDecimal.ONE).toPlainString()));
      assertThat(orderStatus(job)).isEqualTo("REVIEW_REQUIRED");
      assertThat(blocked(job)).isTrue();
    }
  }

  @Test
  void unknownFailureNotAppliedAtProviderResendsWithSameKey() {
    long job = deposit();
    long refundId = refund(job, "3000");
    when(toss.cancel(eq(paymentKey(job)), any(), eq(REASON), anyString()))
        .thenThrow(new TossClientException(500, "FAILED_INTERNAL_SYSTEM_PROCESSING", new RuntimeException()))
        .thenReturn(payment(job, "7000", cancel("tx-2", "3000")));
    executions.execute(refundId);
    when(toss.getPayment(paymentKey(job))).thenReturn(payment(job, "10000"));
    makeDue(refundId);
    executions.execute(refundId);

    verify(toss, times(2)).cancel(eq(paymentKey(job)), any(), eq(REASON), eq("refund-" + refundId));
    assertThat(read(refundId).status()).isEqualTo("SUCCEEDED");
  }

  @ParameterizedTest
  @CsvSource({
      "400,ALREADY_CANCELED_PAYMENT", "400,ALREADY_REFUND_PAYMENT",
      "400,EXCEED_CANCEL_AMOUNT_DISCOUNT_AMOUNT", "400,INVALID_REQUEST",
      "400,NOT_MATCHES_REFUNDABLE_AMOUNT", "400,REFUND_REJECTED",
      "403,EXCEED_CANCEL_LIMIT", "403,EXCEED_MAX_REFUND_DUE", "403,NOT_ALLOWED_PARTIAL_REFUND",
      "403,NOT_CANCELABLE_AMOUNT", "403,NOT_CANCELABLE_PAYMENT",
      "403,NOT_CANCELABLE_PAYMENT_FOR_DORMANT_USER", "404,NOT_FOUND_PAYMENT"
  })
  void definitiveRejectionFailsOnlyAfterQueryConfirmsNotApplied(int status, String code) {
    long job = deposit();
    long refundId = refund(job, "3000");
    cancelFails(job, status, code);
    when(toss.getPayment(paymentKey(job))).thenReturn(payment(job, "10000"));
    executions.execute(refundId);

    RefundResponse refund = read(refundId);
    assertThat(refund.status()).isEqualTo("FAILED");
    assertThat(refund.failureCode()).isEqualTo(code);
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("0");
    assertThat(balance(job, "refunded_amount")).isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund_histories WHERE refund_id = ? AND"
        + " next_status = 'FAILED' AND reason = ?", Long.class, refundId, code)).isEqualTo(1);
    // 실패한 환불 금액은 다시 쓸 수 있다.
    assertThat(read(refund(job, "10000")).status()).isEqualTo("REQUESTED");
  }

  @ParameterizedTest
  @CsvSource({
      "500,INVALID_REQUEST", "503,NOT_CANCELABLE_AMOUNT", "500,NOT_FOUND_PAYMENT",
      "400,NOT_CANCELABLE_AMOUNT", "403,INVALID_REQUEST", "404,INVALID_REQUEST",
      "401,INVALID_REQUEST", "429,INVALID_REQUEST", ",INVALID_REQUEST", "400,",
      "400,UNKNOWN_ERROR"
  })
  void mismatchedRejectionKeepsReservedAmountAndRetriesSameRefund(Integer status, String code) {
    long job = deposit();
    long refundId = refund(job, "3000");
    cancelFails(job, status, code);
    when(toss.getPayment(paymentKey(job))).thenReturn(payment(job, "10000"));
    executions.execute(refundId);

    assertThat(read(refundId).status()).isEqualTo("REQUESTED");
    assertThat(read(refundId).reviewRequired()).isFalse();
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("3000");
    assertThat(balance(job, "refunded_amount")).isEqualByComparingTo("0");
    assertThat(blocked(job)).isFalse();
    verify(toss, never()).getPayment(paymentKey(job));

    cancelReturns(job, payment(job, "7000", cancel("tx-retry", "3000")));
    makeDue(refundId);
    executions.execute(refundId);
    assertThat(read(refundId).status()).isEqualTo("SUCCEEDED");
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("0");
    assertThat(balance(job, "refunded_amount")).isEqualByComparingTo("3000");
    verify(toss, times(2)).cancel(eq(paymentKey(job)), any(), eq(REASON), eq("refund-" + refundId));
  }

  @Test
  void rejectionCodeDoesNotOverrideAppliedCancellation() {
    long job = deposit();
    long refundId = refund(job, "3000");
    cancelFails(job, 400, "ALREADY_CANCELED_PAYMENT");
    when(toss.getPayment(paymentKey(job))).thenReturn(payment(job, "7000", cancel("tx-3", "3000")));
    executions.execute(refundId);
    assertThat(read(refundId).status()).isEqualTo("SUCCEEDED");
    assertThat(balance(job, "refunded_amount")).isEqualByComparingTo("3000");
  }

  @Test
  void retryableProviderCodesStayRequested() {
    for (String code : List.of("PROVIDER_ERROR", "FORBIDDEN_CONSECUTIVE_REQUEST", "UNAUTHORIZED_KEY")) {
      long job = deposit();
      long refundId = refund(job, "3000");
      cancelFails(job, 400, code);
      executions.execute(refundId);
      assertThat(read(refundId).status()).as(code).isEqualTo("REQUESTED");
      assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("3000");
      verify(toss, never()).getPayment(paymentKey(job));
    }
  }

  @Test
  void unexplainedCancellationStopsRefundAndBlocksDeposit() {
    long job = deposit();
    long refundId = refund(job, "3000");
    cancelReturns(job, payment(job, "5000", cancel("tx-x", "2000"), cancel("tx-4", "3000")));
    executions.execute(refundId);

    RefundResponse refund = read(refundId);
    assertThat(refund.status()).isEqualTo("REQUESTED");
    assertThat(refund.reviewRequired()).isTrue();
    assertThat(refund.failureCode()).isEqualTo("UNEXPLAINED_CANCELLATION");
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("3000");
    assertThat(blocked(job)).isTrue();
    assertThat(orderStatus(job)).isEqualTo("REVIEW_REQUIRED");
    makeDue(refundId);
    executions.execute(refundId);
    verify(toss, times(1)).cancel(any(), any(), any(), any());
  }

  @Test
  void providerPaymentMismatchStopsRefundWithoutBlocking() {
    long job = deposit();
    long refundId = refund(job, "3000");
    var other = new TossPayment(paymentKey(job), "other-order", "PARTIAL_CANCELED", "KRW", "카드",
        new BigDecimal("10000"), new BigDecimal("7000"), OffsetDateTime.now());
    cancelReturns(job, other);
    executions.execute(refundId);
    assertThat(read(refundId).reviewRequired()).isTrue();
    assertThat(read(refundId).failureCode()).isEqualTo("PROVIDER_MISMATCH");
    assertThat(blocked(job)).isFalse();
  }

  @Test
  void providerPaymentWithoutStatusStopsRefundAsMismatch() {
    long job = deposit();
    long refundId = refund(job, "3000");
    cancelReturns(job, new TossPayment(paymentKey(job), orderId(job), null, "KRW", "카드",
        new BigDecimal("10000"), new BigDecimal("7000"), OffsetDateTime.now()));
    executions.execute(refundId);
    assertThat(read(refundId).status()).isEqualTo("REQUESTED");
    assertThat(read(refundId).failureCode()).isEqualTo("PROVIDER_MISMATCH");
    assertThat(blocked(job)).isFalse();
  }

  @Test
  void schedulerIsNotRegisteredWhenRecoveryIsDisabled() {
    assertThat(context.getBeanNamesForType(RefundRecovery.class)).isEmpty();
  }

  @Test
  void refundsOfOneOrderRunInRequestOrder() {
    long job = deposit();
    long first = refund(job, "3000");
    long second = refund(job, "2000");
    executions.execute(second);
    verify(toss, never()).cancel(any(), any(), any(), any());

    cancelReturns(job, payment(job, "7000", cancel("tx-1", "3000")));
    executions.execute(first);
    cancelReturns(job, payment(job, "5000", cancel("tx-1", "3000"), cancel("tx-2", "2000")));
    executions.execute(second);
    assertThat(read(first).status()).isEqualTo("SUCCEEDED");
    assertThat(read(second).status()).isEqualTo("SUCCEEDED");
    assertThat(jdbc.queryForObject("SELECT provider_transaction_key FROM payment_refunds WHERE id = ?",
        String.class, second)).isEqualTo("tx-2");
    assertThat(balance(job, "refunded_amount")).isEqualByComparingTo("5000");
  }

  @Test
  void expiredIdempotencyWindowStopsAutomaticResend() {
    long job = deposit();
    long refundId = refund(job, "3000");
    cancelFails(job, null, null);
    executions.execute(refundId);
    jdbc.update("UPDATE payment_refunds SET first_attempted_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 15 DAY)"
        + " WHERE id = ?", refundId);
    makeDue(refundId);
    executions.execute(refundId);
    assertThat(read(refundId).failureCode()).isEqualTo("IDEMPOTENCY_WINDOW_EXPIRED");
    assertThat(read(refundId).reviewRequired()).isTrue();
    verify(toss, times(1)).cancel(any(), any(), any(), any());
    verify(toss, never()).getPayment(any());
  }

  @Test
  void refundOfBlockedDepositIsNotExecuted() {
    long job = deposit();
    long refundId = refund(job, "3000");
    jdbc.update("UPDATE payment_deposits SET funding_blocked = TRUE WHERE job_post_id = ?", job);
    executions.execute(refundId);
    verifyNoInteractions(toss);
    assertThat(read(refundId).status()).isEqualTo("REQUESTED");
  }

  @Test
  void staleWorkerCannotSettleAfterLeaseTakeover() {
    long job = deposit();
    long refundId = refund(job, "3000");
    var stale = transactions.claim(refundId);
    assertThat(transactions.claim(refundId)).isNull();
    jdbc.update("UPDATE payment_refunds SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND)"
        + " WHERE id = ?", refundId);
    var current = transactions.claim(refundId);
    assertThat(current.attempt()).isEqualTo(2);
    var applied = payment(job, "7000", cancel("tx-1", "3000"));
    assertThat(transactions.settle(stale, applied, null)).isTrue();
    assertThat(read(refundId).status()).isEqualTo("REQUESTED");
    assertThat(transactions.settle(current, applied, null)).isTrue();
    assertThat(read(refundId).status()).isEqualTo("SUCCEEDED");
  }
}
