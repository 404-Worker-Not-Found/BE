package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.workernotfound.payment.domain.refund.dto.request.RefundRequest;
import com.workernotfound.payment.domain.refund.service.RefundApplicationService;
import com.workernotfound.payment.domain.refund.service.RefundExecutionService;
import com.workernotfound.payment.external.job.FundingJobClient;
import com.workernotfound.payment.external.toss.*;
import com.workernotfound.payment.global.account.AccountGateService;
import com.workernotfound.payment.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// 예치가 정산 지급·환불로 모두 끝났는지에 따라 점주 탈퇴 차단을 판단한다.
class DepositClosureTests extends IntegrationTestSupport {
  static final AtomicLong IDS = new AtomicLong(960_000);
  static final String REASON = "미사용 예치금 반환";
  @Autowired AccountGateService gates;
  @Autowired RefundApplicationService refunds;
  @Autowired RefundExecutionService executions;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean TossGateway toss;
  @MockitoBean FundingJobClient job;

  record Fixture(long owner, long job) {}

  // 검증된 승인으로 만든 1만 원 예치와 DEPOSITED 주문을 흉내 낸다. 회원마다 따로 만든다.
  Fixture deposit() {
    long owner = IDS.incrementAndGet(), job = IDS.incrementAndGet();
    jdbc.update("INSERT INTO payment_deposits(job_post_id,owner_member_id,currency,deposited_amount,locked_amount,"
        + "version) VALUES (?,?,'KRW',10000,0,0)", job, owner);
    order(owner, job, "DEPOSITED");
    return new Fixture(owner, job);
  }

  void order(long owner, long job, String status) {
    jdbc.update("INSERT INTO payment_orders(id,job_post_id,job_version,owner_member_id,amount,currency,status,"
        + "payment_key,approved_at) VALUES (?,?,1,?,10000,'KRW',?,?,CURRENT_TIMESTAMP(6))",
        "closure-" + job + "-" + status, job, owner, status,
        "DEPOSITED".equals(status) ? "closure-pk-" + job : "closure-pk-" + job + "-" + status);
  }

  void amounts(Fixture fixture, String paid, String refunded) {
    jdbc.update("UPDATE payment_deposits SET settlement_paid_amount=?, refunded_amount=? WHERE job_post_id=?",
        new BigDecimal(paid), new BigDecimal(refunded), fixture.job());
  }

  String prepare(long member) {
    return gates.transition(member, UUID.randomUUID().toString(), "prepare").state();
  }

  @Test
  void fullyRefundedDepositNoLongerBlocksWithdrawal() {
    Fixture fixture = deposit();
    var refund = refunds.request(UUID.randomUUID().toString(),
        new RefundRequest(fixture.job(), fixture.owner(), new BigDecimal("10000"), "KRW", REASON));
    assertThat(prepare(fixture.owner())).isEqualTo("REJECTED");

    when(toss.cancel(eq("closure-pk-" + fixture.job()), any(), eq(REASON), anyString())).thenReturn(
        new TossPayment("closure-pk-" + fixture.job(), "closure-" + fixture.job() + "-DEPOSITED", "CANCELED",
            "KRW", "카드", new BigDecimal("10000"), BigDecimal.ZERO, OffsetDateTime.now(),
            List.of(new TossPayment.Cancel("tx-all", new BigDecimal("10000"), "DONE", OffsetDateTime.now()))));
    executions.execute(Long.parseLong(refund.refundId()));

    assertThat(refunds.getRefund(Long.parseLong(refund.refundId())).status()).isEqualTo("SUCCEEDED");
    assertThat(prepare(fixture.owner())).isEqualTo("PREPARED");
  }

  @Test
  void depositSplitIntoPaidSettlementAndRefundIsClosed() {
    Fixture fixture = deposit();
    amounts(fixture, "4000", "6000");
    assertThat(prepare(fixture.owner())).isEqualTo("PREPARED");
  }

  @Test
  void remainingBalanceOrBlockedDepositKeepsBlocking() {
    Fixture partial = deposit();
    amounts(partial, "0", "9999");
    assertThat(prepare(partial.owner())).isEqualTo("REJECTED");

    Fixture blocked = deposit();
    amounts(blocked, "0", "10000");
    jdbc.update("UPDATE payment_deposits SET funding_blocked=TRUE WHERE job_post_id=?", blocked.job());
    assertThat(prepare(blocked.owner())).isEqualTo("REJECTED");
  }

  @Test
  void closedDepositDoesNotReleaseOtherOrdersOrDeposits() {
    Fixture reviewing = deposit();
    amounts(reviewing, "0", "10000");
    order(reviewing.owner(), reviewing.job(), "REVIEW_REQUIRED");
    assertThat(prepare(reviewing.owner())).isEqualTo("REJECTED");

    Fixture other = deposit();
    amounts(other, "0", "10000");
    long openJob = IDS.incrementAndGet();
    jdbc.update("INSERT INTO payment_deposits(job_post_id,owner_member_id,currency,deposited_amount,locked_amount,"
        + "version) VALUES (?,?,'KRW',10000,0,0)", openJob, other.owner());
    assertThat(prepare(other.owner())).isEqualTo("REJECTED");
  }

  @Test
  void depositedOrderWithoutDepositKeepsBlocking() {
    long owner = IDS.incrementAndGet();
    order(owner, IDS.incrementAndGet(), "DEPOSITED");
    assertThat(prepare(owner)).isEqualTo("REJECTED");
  }
}
