package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.refund.dto.request.RefundRequest;
import com.workernotfound.payment.domain.refund.dto.response.*;
import com.workernotfound.payment.domain.refund.service.*;
import com.workernotfound.payment.external.job.FundingJobClient;
import com.workernotfound.payment.external.toss.*;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
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
}
