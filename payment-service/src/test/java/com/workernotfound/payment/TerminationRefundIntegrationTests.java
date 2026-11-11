package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.payment.service.PaymentApplicationService;
import com.workernotfound.payment.domain.refund.dto.request.RefundRequest;
import com.workernotfound.payment.domain.refund.service.RefundApplicationService;
import com.workernotfound.payment.domain.refund.service.RefundExecutionService;
import com.workernotfound.payment.domain.settlement.service.MatchConfirmation;
import com.workernotfound.payment.domain.settlement.service.SettlementReservationService;
import com.workernotfound.payment.domain.settlement.service.SettlementTargetService;
import com.workernotfound.payment.domain.settlement.service.WorkCompletion;
import com.workernotfound.payment.domain.settlement.service.WorkTerminationEvent;
import com.workernotfound.payment.domain.settlement.service.WorkTerminationService;
import com.workernotfound.payment.external.toss.TossGateway;
import com.workernotfound.payment.external.toss.TossPayment;
import com.workernotfound.payment.global.exception.BusinessException;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 노쇼·확정 근무 취소로 해제한 잠금 금액을 같은 트랜잭션에서 점주 환불로 요청한다(#167). 1000원 예치에서 600원 잠금을 쓴다.
@TestPropertySource(properties = "payment.termination-refund.enabled=true")
class TerminationRefundIntegrationTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(3_500_000);
  static final LocalDateTime AT = LocalDateTime.of(2026, 11, 12, 18, 0);
  static final String NO_SHOW_REASON = "노쇼 근무의 미사용 예치금 환불";
  @Autowired PaymentApplicationService payments;
  @Autowired PaymentDepositRepository deposits;
  @Autowired SettlementTargetService targets;
  @Autowired SettlementReservationService settlements;
  @Autowired WorkTerminationService terminations;
  @Autowired RefundApplicationService refunds;
  @Autowired RefundExecutionService executions;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean TossGateway toss;

  record Fixture(long job, long matching, long work, long payment) {}

  Fixture fixture(boolean hasDepositedOrder) {
    long job = IDS.incrementAndGet(), matching = IDS.incrementAndGet(), work = IDS.incrementAndGet();
    deposits.saveAndFlush(PaymentDeposit.builder().jobPostId(job).ownerMemberId(20L).currency("KRW")
        .depositedAmount(new BigDecimal("1000")).build());
    if (hasDepositedOrder) jdbc.update("INSERT INTO payment_orders(id,job_post_id,job_version,owner_member_id,amount,"
        + "currency,status,payment_key,approved_at) VALUES (?,?,1,20,1000,'KRW','DEPOSITED',?,CURRENT_TIMESTAMP(6))",
        orderId(job), job, paymentKey(job));
    long payment = Long.parseLong(payments.lock(UUID.randomUUID().toString(),
        new PaymentLockRequest(matching, job, 20L, 30L, new BigDecimal("600"), "KRW")).paymentId());
    targets.record(new MatchConfirmation(UUID.randomUUID().toString(), matching, 2L, work, payment, job, 20L, 30L,
        new BigDecimal("600"), "KRW", AT.minusHours(9)));
    return new Fixture(job, matching, work, payment);
  }

  String orderId(long job) { return "termination-order-" + job; }

  String paymentKey(long job) { return "termination-pk-" + job; }

  WorkTerminationEvent event(Fixture fixture, String type) {
    boolean noShow = type.equals("WorkNoShow");
    return new WorkTerminationEvent(UUID.randomUUID().toString(), type, fixture.work(), noShow ? 4 : 5, 1, AT,
        fixture.work(), fixture.matching(), fixture.job(), 20L, 30L, noShow ? null : 20L, noShow ? "SYSTEM" : "OWNER",
        noShow ? "NO_SHOW" : "CANCELED");
  }

  Map<String, Object> deposit(Fixture fixture) {
    return jdbc.queryForMap("SELECT locked_amount, refund_reserved_amount, refunded_amount FROM payment_deposits"
        + " WHERE job_post_id=?", fixture.job());
  }

  Map<String, Object> termination(Fixture fixture) {
    return jdbc.queryForMap("SELECT result, refund_id, refund_outcome FROM payment_work_terminations WHERE work_id=?",
        fixture.work());
  }

  List<Map<String, Object>> refundsOf(Fixture fixture) {
    return jdbc.queryForList("SELECT id, amount, status, reason, order_id FROM payment_refunds WHERE job_post_id=?",
        fixture.job());
  }

  @Test
  void noShowReleaseRequestsRefundOfReleasedAmountOnce() {
    var fixture = fixture(true);

    assertThat(terminations.receive(event(fixture, "WorkNoShow"))).isEqualTo("RELEASED");
    assertThat(terminations.receive(event(fixture, "WorkNoShow"))).isEqualTo("RELEASED");

    var refund = refundsOf(fixture);
    assertThat(refund).hasSize(1);
    assertThat((BigDecimal) refund.get(0).get("amount")).isEqualByComparingTo("600");
    assertThat(refund.get(0)).containsEntry("status", "REQUESTED").containsEntry("reason", NO_SHOW_REASON)
        .containsEntry("order_id", orderId(fixture.job()));
    assertThat((BigDecimal) deposit(fixture).get("locked_amount")).isEqualByComparingTo("0");
    assertThat((BigDecimal) deposit(fixture).get("refund_reserved_amount")).isEqualByComparingTo("600");
    assertThat(termination(fixture)).containsEntry("result", "RELEASED").containsEntry("refund_outcome", "REQUESTED")
        .containsEntry("refund_id", refund.get(0).get("id"));
  }

  @Test
  void cancellationRefundUsesCancellationReason() {
    var fixture = fixture(true);

    terminations.receive(event(fixture, "WorkCanceled"));

    assertThat(refundsOf(fixture)).singleElement()
        .satisfies(row -> assertThat(row).containsEntry("reason", "확정 근무 취소의 미사용 예치금 환불"));
  }

  @Test
  void unreleasedTerminationsDoNotRefund() {
    var blocked = fixture(true);
    jdbc.update("UPDATE payment_deposits SET funding_blocked=TRUE WHERE job_post_id=?", blocked.job());
    var transferred = fixture(true);
    settlements.reserve(new WorkCompletion(UUID.randomUUID().toString(), transferred.work(), transferred.matching(),
        transferred.job(), 20L, 30L, AT));

    assertThat(terminations.receive(event(blocked, "WorkNoShow"))).isEqualTo("REVIEW_REQUIRED");
    assertThat(terminations.receive(event(transferred, "WorkNoShow"))).isEqualTo("REVIEW_REQUIRED");

    assertThat(refundsOf(blocked)).isEmpty();
    assertThat(refundsOf(transferred)).isEmpty();
    assertThat(termination(blocked)).containsEntry("refund_outcome", null);
  }

  @Test
  void unresolvedDepositOrderKeepsReleaseWithoutRefund() {
    var fixture = fixture(false);

    assertThat(terminations.receive(event(fixture, "WorkNoShow"))).isEqualTo("RELEASED");

    assertThat(refundsOf(fixture)).isEmpty();
    assertThat((BigDecimal) deposit(fixture).get("locked_amount")).isEqualByComparingTo("0");
    assertThat((BigDecimal) deposit(fixture).get("refund_reserved_amount")).isEqualByComparingTo("0");
    assertThat(termination(fixture)).containsEntry("refund_outcome", "ORDER_UNRESOLVED").containsEntry("refund_id", null);
  }

  @Test
  void requestedRefundIsExecutedByExistingRefundScheduler() {
    var fixture = fixture(true);
    terminations.receive(event(fixture, "WorkNoShow"));
    long refundId = ((Number) refundsOf(fixture).get(0).get("id")).longValue();
    when(toss.cancel(eq(paymentKey(fixture.job())), any(), eq(NO_SHOW_REASON), eq("refund-" + refundId)))
        .thenReturn(new TossPayment(paymentKey(fixture.job()), orderId(fixture.job()), "PARTIAL_CANCELED", "KRW", "카드",
            new BigDecimal("1000"), new BigDecimal("400"), OffsetDateTime.now(),
            List.of(new TossPayment.Cancel("termination-tx-" + refundId, new BigDecimal("600"), "DONE",
                OffsetDateTime.now()))));

    executions.execute(refundId);

    verify(toss).cancel(eq(paymentKey(fixture.job())), any(), eq(NO_SHOW_REASON), eq("refund-" + refundId));
    assertThat(refundsOf(fixture).get(0)).containsEntry("status", "SUCCEEDED");
    assertThat((BigDecimal) deposit(fixture).get("refunded_amount")).isEqualByComparingTo("600");
    assertThat((BigDecimal) deposit(fixture).get("refund_reserved_amount")).isEqualByComparingTo("0");
  }

  // 종료 환불과 외부 환불 요청이 같은 예치에서 동시에 일어나도 예치 행 잠금으로 직렬화돼 예치 총액을 넘겨 예약하지 않는다.
  @Test
  void concurrentExternalRefundCannotOverspendReleasedAmount() throws Exception {
    var fixture = fixture(true);
    var executor = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var terminate = executor.submit(() -> { start.await(); return terminations.receive(event(fixture, "WorkNoShow")); });
      // 1000원은 어느 순서로 처리돼도 가용 잔액(종료 전 400원, 종료 환불 뒤 400원)을 넘으므로 잔액 부족 거절만 예상한다.
      // 그 밖의 예외(교착·SQL 오류 등)는 거절로 삼키지 않고 테스트 실패로 드러낸다.
      var external = executor.submit(() -> {
        start.await();
        try {
          refunds.request(UUID.randomUUID().toString(),
              new RefundRequest(fixture.job(), 20L, new BigDecimal("1000"), "KRW", "모집 종료 후 미사용 예치금"));
          return null;
        } catch (BusinessException rejected) {
          return rejected.getErrorCode();
        }
      });
      start.countDown();
      assertThat(terminate.get(10, TimeUnit.SECONDS)).isEqualTo("RELEASED");
      assertThat(external.get(10, TimeUnit.SECONDS)).isEqualTo(PaymentErrorCode.INSUFFICIENT_DEPOSIT);
    } finally {
      executor.shutdownNow();
    }

    assertThat((BigDecimal) deposit(fixture).get("refund_reserved_amount")).isEqualByComparingTo("600");
    assertThat(refundsOf(fixture)).singleElement()
        .satisfies(row -> assertThat(row).containsEntry("reason", NO_SHOW_REASON));
  }
}
