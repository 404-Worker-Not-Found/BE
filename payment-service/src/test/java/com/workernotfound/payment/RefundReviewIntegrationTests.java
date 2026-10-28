package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.refund.dto.request.RefundRequest;
import com.workernotfound.payment.domain.refund.dto.request.RefundReviewResumeRequest;
import com.workernotfound.payment.domain.refund.dto.response.*;
import com.workernotfound.payment.domain.refund.service.*;
import com.workernotfound.payment.external.job.FundingJobClient;
import com.workernotfound.payment.external.toss.*;
import com.workernotfound.payment.global.exception.BusinessException;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class RefundReviewIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(900_000);
  static final String SECRET = "payment-test-internal-secret";
  static final String REASON = "모집 종료 후 미사용 예치금";
  @Autowired RefundApplicationService refunds;
  @Autowired RefundExecutionService executions;
  @Autowired PaymentDepositRepository deposits;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
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

  String orderId(long job) { return "review-order-" + job; }

  String paymentKey(long job) { return "review-pk-" + job; }

  long refund(long job, String amount) {
    RefundResponse refund = refunds.request(UUID.randomUUID().toString(),
        new RefundRequest(job, 20L, new BigDecimal(amount), "KRW", REASON));
    return Long.parseLong(refund.refundId());
  }

  TossPayment payment(long job, String orderId, String balance, TossPayment.Cancel... cancels) {
    String status = "10000".equals(balance) ? "DONE" : "PARTIAL_CANCELED";
    return new TossPayment(paymentKey(job), orderId, status, "KRW", "카드", new BigDecimal("10000"),
        new BigDecimal(balance), OffsetDateTime.now(), List.of(cancels));
  }

  TossPayment.Cancel cancel(String transactionKey, String amount) {
    return new TossPayment.Cancel(transactionKey, new BigDecimal(amount), "DONE", OffsetDateTime.now());
  }

  void cancelReturns(long job, TossPayment payment) {
    when(toss.cancel(eq(paymentKey(job)), any(), eq(REASON), anyString())).thenReturn(payment);
  }

  // 취소 응답의 주문 ID가 다르면 금액을 판단하지 않고 검토로 멈춘다.
  long providerMismatch(long job) {
    long refundId = refund(job, "3000");
    cancelReturns(job, payment(job, "other-order", "7000", cancel("tx-m", "3000")));
    executions.execute(refundId);
    return refundId;
  }

  // 장부로 설명되지 않는 취소 합계는 검토로 멈추고 예치를 차단한다.
  long unexplainedCancellation(long job) {
    long refundId = refund(job, "3000");
    cancelReturns(job, payment(job, orderId(job), "5000", cancel("tx-x", "2000"), cancel("tx-u", "3000")));
    executions.execute(refundId);
    return refundId;
  }

  // 응답을 잃은 뒤 멱등 키 유효 기간이 지나면 다시 보내지 않고 검토로 멈춘다.
  long idempotencyWindowExpired(long job) {
    long refundId = refund(job, "3000");
    when(toss.cancel(eq(paymentKey(job)), any(), eq(REASON), anyString()))
        .thenThrow(new TossClientException(null, null, new RuntimeException("provider")));
    executions.execute(refundId);
    jdbc.update("UPDATE payment_refunds SET first_attempted_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 15 DAY),"
        + " next_attempt_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", refundId);
    executions.execute(refundId);
    return refundId;
  }

  RefundResponse read(long refundId) { return refunds.getRefund(refundId); }

  RefundReviewResumeRequest resumeRequest(String reviewReason) {
    return new RefundReviewResumeRequest("ops.kim", reviewReason, "토스 설정 확인 후 재개");
  }

  RefundResponse resume(long refundId, String key, String reviewReason) {
    return refunds.resumeReview(refundId, key, resumeRequest(reviewReason));
  }

  void assertRejected(PaymentErrorCode code, Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
  }

  long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  BigDecimal balance(long job, String column) {
    return jdbc.queryForObject("SELECT " + column + " FROM payment_deposits WHERE job_post_id = ?",
        BigDecimal.class, job);
  }

  @Test
  void reviewListShowsOnlyStoppedRefundsWithResumability() {
    long mismatched = providerMismatch(deposit());
    long unexplained = unexplainedCancellation(deposit());
    long pending = refund(deposit(), "1000");
    long expired = idempotencyWindowExpired(deposit());

    RefundReviewPageResponse first = refunds.getReviews(mismatched - 1, 2);
    assertThat(first.reviews()).extracting(RefundReviewResponse::refundId)
        .containsExactly(String.valueOf(mismatched), String.valueOf(unexplained));
    assertThat(first.hasNext()).isTrue();
    assertThat(first.nextAfterRefundId()).isEqualTo(unexplained);
    RefundReviewResponse mismatch = first.reviews().get(0);
    assertThat(mismatch.reviewReason()).isEqualTo("PROVIDER_MISMATCH");
    assertThat(mismatch.attempts()).isEqualTo(1);
    assertThat(mismatch.firstAttemptedAt()).isNotNull();
    assertThat(mismatch.amount()).isEqualByComparingTo("3000");
    assertThat(mismatch.fundingBlocked()).isFalse();
    assertThat(mismatch.resumable()).isTrue();
    RefundReviewResponse blocked = first.reviews().get(1);
    assertThat(blocked.reviewReason()).isEqualTo("UNEXPLAINED_CANCELLATION");
    assertThat(blocked.fundingBlocked()).isTrue();
    assertThat(blocked.resumable()).isFalse();

    RefundReviewPageResponse second = refunds.getReviews(first.nextAfterRefundId(), 2);
    assertThat(second.reviews()).extracting(RefundReviewResponse::refundId)
        .containsExactly(String.valueOf(expired)).doesNotContain(String.valueOf(pending));
    assertThat(second.reviews().get(0).reviewReason()).isEqualTo("IDEMPOTENCY_WINDOW_EXPIRED");
    assertThat(second.reviews().get(0).resumable()).isTrue();
    assertThat(second.hasNext()).isFalse();

    RefundReviewPageResponse empty = refunds.getReviews(expired, 2);
    assertThat(empty.reviews()).isEmpty();
    assertThat(empty.nextAfterRefundId()).isEqualTo(expired);
    assertThat(empty.hasNext()).isFalse();
  }

  @Test
  void resumedProviderMismatchQueriesFirstAndUnblocksLaterRefundsOfOrder() {
    long job = deposit();
    long refundId = providerMismatch(job);
    long later = refund(job, "2000");
    executions.execute(later);
    executions.execute(refundId);
    verify(toss, times(1)).cancel(any(), any(), any(), any());

    RefundResponse resumed = resume(refundId, UUID.randomUUID().toString(), "PROVIDER_MISMATCH");
    assertThat(resumed.status()).isEqualTo("REQUESTED");
    assertThat(resumed.reviewRequired()).isFalse();
    assertThat(resumed.failureCode()).isNull();
    assertThat(balance(job, "refund_reserved_amount")).isEqualByComparingTo("5000");
    assertThat(count("SELECT COUNT(*) FROM payment_refund_histories WHERE refund_id = ? AND"
        + " previous_status = 'REQUESTED' AND next_status = 'REQUESTED' AND reason = 'REVIEW_RESUMED'",
        refundId)).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM payment_refund_review_resumptions WHERE refund_id = ? AND"
        + " operator_id = 'ops.kim' AND note = '토스 설정 확인 후 재개' AND resumed_reason = 'PROVIDER_MISMATCH'"
        + " AND rejection IS NULL", refundId)).isEqualTo(1);

    // 첫 취소가 PG에 반영돼 있었다면 다시 보내지 않고 조회로 성공을 확정한다.
    when(toss.getPayment(paymentKey(job))).thenReturn(payment(job, orderId(job), "7000", cancel("tx-m", "3000")));
    executions.execute(refundId);
    assertThat(read(refundId).status()).isEqualTo("SUCCEEDED");
    assertThat(balance(job, "refunded_amount")).isEqualByComparingTo("3000");
    verify(toss, times(1)).cancel(any(), any(), any(), any());

    cancelReturns(job, payment(job, orderId(job), "5000", cancel("tx-m", "3000"), cancel("tx-l", "2000")));
    executions.execute(later);
    assertThat(read(later).status()).isEqualTo("SUCCEEDED");
    assertThat(balance(job, "refunded_amount")).isEqualByComparingTo("5000");
  }

  @Test
  void resumedExpiredWindowRestartsWindowAndResendsOnlyAfterQueryShowsNotApplied() {
    long job = deposit();
    long refundId = idempotencyWindowExpired(job);
    resume(refundId, UUID.randomUUID().toString(), "IDEMPOTENCY_WINDOW_EXPIRED");
    assertThat(jdbc.queryForObject("SELECT first_attempted_at IS NULL FROM payment_refunds WHERE id = ?",
        Boolean.class, refundId)).isTrue();
    assertThat(jdbc.queryForObject("SELECT attempts FROM payment_refunds WHERE id = ?", Integer.class, refundId))
        .isEqualTo(2);

    reset(toss);
    when(toss.getPayment(paymentKey(job))).thenReturn(payment(job, orderId(job), "10000"));
    cancelReturns(job, payment(job, orderId(job), "7000", cancel("tx-r", "3000")));
    executions.execute(refundId);

    var order = inOrder(toss);
    order.verify(toss).getPayment(paymentKey(job));
    order.verify(toss).cancel(eq(paymentKey(job)), any(), eq(REASON), eq("refund-" + refundId));
    assertThat(read(refundId).status()).isEqualTo("SUCCEEDED");
    assertThat(jdbc.queryForObject("SELECT first_attempted_at IS NOT NULL FROM payment_refunds WHERE id = ?",
        Boolean.class, refundId)).isTrue();
  }

  @Test
  void sameKeyReplaysFirstDecisionAndRejectedKeyNeverSucceeds() {
    long job = deposit();
    long refundId = providerMismatch(job);
    String key = UUID.randomUUID().toString();
    resume(refundId, key, "PROVIDER_MISMATCH");
    assertThat(resume(refundId, key, "PROVIDER_MISMATCH").reviewRequired()).isFalse();
    assertThat(count("SELECT COUNT(*) FROM payment_refund_histories WHERE refund_id = ? AND reason ="
        + " 'REVIEW_RESUMED'", refundId)).isEqualTo(1);
    assertRejected(PaymentErrorCode.COMMAND_CONFLICT, () -> refunds.resumeReview(refundId, key,
        new RefundReviewResumeRequest("ops.kim", "PROVIDER_MISMATCH", "다른 메모")));
    long other = providerMismatch(deposit());
    assertRejected(PaymentErrorCode.COMMAND_CONFLICT, () -> resume(other, key, "PROVIDER_MISMATCH"));

    // 이미 재개한 환불에 새 키를 쓰면 거절되고, 같은 환불이 다시 검토로 멈춰도 그 키는 성공하지 않는다.
    String late = UUID.randomUUID().toString();
    assertRejected(PaymentErrorCode.REFUND_REVIEW_MISMATCH, () -> resume(refundId, late, "PROVIDER_MISMATCH"));
    when(toss.getPayment(paymentKey(job))).thenReturn(payment(job, "other-order", "7000"));
    executions.execute(refundId);
    assertThat(read(refundId).reviewRequired()).isTrue();
    assertRejected(PaymentErrorCode.REFUND_REVIEW_MISMATCH, () -> resume(refundId, late, "PROVIDER_MISMATCH"));
    assertThat(resume(refundId, UUID.randomUUID().toString(), "PROVIDER_MISMATCH").reviewRequired()).isFalse();
  }

  @Test
  void unresumableReviewsAreRejectedWithoutChangingRefund() {
    long unexplained = unexplainedCancellation(deposit());
    assertRejected(PaymentErrorCode.REFUND_REVIEW_NOT_RESUMABLE,
        () -> resume(unexplained, UUID.randomUUID().toString(), "UNEXPLAINED_CANCELLATION"));
    assertThat(read(unexplained).reviewRequired()).isTrue();
    assertThat(read(unexplained).failureCode()).isEqualTo("UNEXPLAINED_CANCELLATION");

    long blockedJob = deposit();
    long blocked = providerMismatch(blockedJob);
    jdbc.update("UPDATE payment_deposits SET funding_blocked = TRUE WHERE job_post_id = ?", blockedJob);
    assertRejected(PaymentErrorCode.FUNDING_BLOCKED,
        () -> resume(blocked, UUID.randomUUID().toString(), "PROVIDER_MISMATCH"));
    assertRejected(PaymentErrorCode.REFUND_REVIEW_MISMATCH,
        () -> resume(blocked, UUID.randomUUID().toString(), "IDEMPOTENCY_WINDOW_EXPIRED"));
    assertThat(read(blocked).reviewRequired()).isTrue();
    assertThat(count("SELECT COUNT(*) FROM payment_refund_review_resumptions WHERE refund_id IN (?, ?) AND"
        + " rejection IS NOT NULL AND resumed_reason IS NULL", unexplained, blocked)).isEqualTo(3);

    long pendingJob = deposit();
    long pending = refund(pendingJob, "1000");
    assertRejected(PaymentErrorCode.REFUND_REVIEW_MISMATCH,
        () -> resume(pending, UUID.randomUUID().toString(), "PROVIDER_MISMATCH"));
    assertThat(jdbc.queryForObject("SELECT next_attempt_at IS NULL FROM payment_refunds WHERE id = ?",
        Boolean.class, pending)).isTrue();

    String missingKey = UUID.randomUUID().toString();
    assertRejected(PaymentErrorCode.REFUND_NOT_FOUND,
        () -> resume(Long.MAX_VALUE, missingKey, "PROVIDER_MISMATCH"));
    assertThat(count("SELECT COUNT(*) FROM payment_refund_review_resumptions WHERE command_key = ?", missingKey))
        .isZero();
  }

  @Test
  void simultaneousResumesWithDifferentKeysResumeOnce() throws Exception {
    long refundId = providerMismatch(deposit());
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      List<Future<String>> results = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        results.add(executor.submit(() -> {
          start.await();
          try {
            resume(refundId, UUID.randomUUID().toString(), "PROVIDER_MISMATCH");
            return "RESUMED";
          } catch (BusinessException exception) {
            return ((PaymentErrorCode) exception.getErrorCode()).name();
          }
        }));
      }
      start.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<String> result : results) outcomes.add(result.get(10, TimeUnit.SECONDS));
      assertThat(outcomes).containsExactlyInAnyOrder("RESUMED", "REFUND_REVIEW_MISMATCH");
    } finally {
      executor.shutdown();
    }
    assertThat(count("SELECT COUNT(*) FROM payment_refund_histories WHERE refund_id = ? AND reason ="
        + " 'REVIEW_RESUMED'", refundId)).isEqualTo(1);
  }

  @Test
  void httpReviewListRequiresInternalSecretAndValidCursor() throws Exception {
    long refundId = providerMismatch(deposit());
    String path = "/api/payments/internal/refunds/reviews";
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).header("X-Internal-Secret", SECRET)
            .param("afterRefundId", String.valueOf(refundId - 1)).param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reviews[0].refundId").value(String.valueOf(refundId)))
        .andExpect(jsonPath("$.data.reviews[0].reviewReason").value("PROVIDER_MISMATCH"))
        .andExpect(jsonPath("$.data.reviews[0].resumable").value(true))
        .andExpect(jsonPath("$.data.nextAfterRefundId").value(refundId));
    mvc.perform(get(path).header("X-Internal-Secret", SECRET)).andExpect(status().isOk());
    for (var invalid : List.of(Map.of("size", "0"), Map.of("size", "101"), Map.of("afterRefundId", "-1"),
        Map.of("size", "x"))) {
      var request = get(path).header("X-Internal-Secret", SECRET);
      invalid.forEach(request::param);
      mvc.perform(request).andExpect(status().isBadRequest());
    }
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/payments/internal/refunds/reviews']").exists());
  }

  String resumeBody(String operatorId, String reviewReason, String note) {
    return """
        {"operatorId":%s,"reviewReason":%s,"note":%s}
        """.formatted(operatorId, reviewReason, note);
  }

  String resumePath(long refundId) {
    return "/api/payments/internal/refunds/" + refundId + "/review-resumptions";
  }

  @Test
  void httpResumeContractAuthenticationAndValidation() throws Exception {
    long refundId = providerMismatch(deposit());
    String body = resumeBody("\"ops.kim\"", "\"PROVIDER_MISMATCH\"", "\"토스 설정 확인\"");
    mvc.perform(post(resumePath(refundId)).header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    mvc.perform(post(resumePath(refundId)).header("X-Internal-Secret", SECRET)
        .contentType("application/json").content(body)).andExpect(status().isBadRequest());
    for (String invalid : List.of(
        resumeBody("\"\"", "\"PROVIDER_MISMATCH\"", "\"메모\""),
        resumeBody("\"ops kim\"", "\"PROVIDER_MISMATCH\"", "\"메모\""),
        resumeBody("\"ops:kim\"", "\"PROVIDER_MISMATCH\"", "\"메모\""),
        resumeBody("\"" + "a".repeat(65) + "\"", "\"PROVIDER_MISMATCH\"", "\"메모\""),
        resumeBody("null", "\"PROVIDER_MISMATCH\"", "\"메모\""),
        resumeBody("\"ops.kim\"", "\"provider_mismatch\"", "\"메모\""),
        resumeBody("\"ops.kim\"", "null", "\"메모\""),
        resumeBody("\"ops.kim\"", "\"PROVIDER_MISMATCH\"", "\" \""),
        resumeBody("\"ops.kim\"", "\"PROVIDER_MISMATCH\"", "\"" + "가".repeat(201) + "\""))) {
      String key = UUID.randomUUID().toString();
      mvc.perform(post(resumePath(refundId)).header("X-Internal-Secret", SECRET).header("Idempotency-Key", key)
          .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
      assertThat(count("SELECT COUNT(*) FROM payment_refund_review_resumptions WHERE command_key = ?", key))
          .isZero();
    }
    mvc.perform(post("/api/payments/internal/refunds/0/review-resumptions").header("X-Internal-Secret", SECRET)
        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content(body))
        .andExpect(status().isBadRequest());
    mvc.perform(post(resumePath(Long.MAX_VALUE)).header("X-Internal-Secret", SECRET)
        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json").content(body))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT-404-002"));

    mvc.perform(post(resumePath(refundId)).header("X-Internal-Secret", SECRET)
        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
        .content(resumeBody("\"ops.kim\"", "\"IDEMPOTENCY_WINDOW_EXPIRED\"", "\"메모\"")))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT-409-008"));
    mvc.perform(post(resumePath(refundId)).header("X-Internal-Secret", SECRET)
        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
        .content(resumeBody("\"ops.kim\"", "\"PROVIDER_MISMATCH\"", "\"" + "가".repeat(200) + "\"")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.refundId").value(String.valueOf(refundId)))
        .andExpect(jsonPath("$.data.status").value("REQUESTED"))
        .andExpect(jsonPath("$.data.reviewRequired").value(false))
        .andExpect(jsonPath("$.data.failureCode").doesNotExist());

    long unexplained = unexplainedCancellation(deposit());
    mvc.perform(post(resumePath(unexplained)).header("X-Internal-Secret", SECRET)
        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
        .content(resumeBody("\"ops.kim\"", "\"UNEXPLAINED_CANCELLATION\"", "\"메모\"")))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT-409-009"));
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/payments/internal/refunds/{refundId}/review-resumptions']").exists());
  }
}
