package com.workernotfound.payment.domain.refund.service;

import com.workernotfound.payment.domain.order.repository.PaymentOrderRepository;
import com.workernotfound.payment.domain.order.repository.PaymentOrderRepository.Order;
import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.refund.entity.*;
import com.workernotfound.payment.domain.refund.repository.*;
import com.workernotfound.payment.domain.refund.repository.RefundExecutionRepository.Claim;
import com.workernotfound.payment.external.toss.TossPayment;
import java.math.BigDecimal;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 PG 결과를 장부에 반영하는 짧은 트랜잭션들. PG 호출은 트랜잭션 밖({@link RefundExecutionService})에서 한다.
 *
 * <p>결과 저장의 잠금 순서는 주문 → 예치 → 환불이다. 결제 재조회도 주문 → 예치 순서이므로 우리 환불을 외부 취소로 오인하는 판단과
 * 직렬화된다. 임대 토큰이 바뀌었으면 다른 작업자가 맡았으므로 아무것도 바꾸지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundExecutionTransactionService {
  private static final Set<String> SETTLED_PAYMENT_STATUSES = Set.of("DONE", "PARTIAL_CANCELED", "CANCELED");

  private final PaymentOrderRepository orders;
  private final PaymentDepositRepository deposits;
  private final PaymentRefundRepository refunds;
  private final RefundExecutionRepository executions;

  @Transactional
  public Claim claim(Long refundId) {
    return executions.claim(refundId);
  }

  /**
   * 조회·취소 응답의 PG 취소 합계로 환불 결과를 확정한다. rejectionCode는 토스가 확정적으로 거절한 취소 요청의 오류 코드다.
   *
   * @return 더 할 일이 없으면 true. 반영되지 않았고 거절도 아니면 false이며 임대를 유지한다.
   */
  @Transactional
  public boolean settle(Claim claim, TossPayment payment, String rejectionCode) {
    Order order = orders.findByIdForUpdate(claim.orderId())
        .orElseThrow(() -> new IllegalStateException("환불 주문이 없습니다."));
    PaymentDeposit deposit = deposits.findByJobPostIdForUpdate(order.jobId())
        .orElseThrow(() -> new IllegalStateException("환불 예치가 없습니다."));
    if (!executions.ownsLease(claim)) return true;
    PaymentRefund refund = refunds.findById(claim.refundId())
        .orElseThrow(() -> new IllegalStateException("환불이 없습니다."));
    if (!isSameProviderPayment(order, payment)) {
      markReview(claim, refund, "PROVIDER_MISMATCH");
      return true;
    }
    return switch (RefundCoverage.of(RefundCoverage.canceledAmount(payment), deposit.getRefundedAmount(),
        refund.getAmount())) {
      case APPLIED -> succeed(claim, refund, deposit, payment);
      case NOT_APPLIED -> rejectionCode != null && fail(claim, refund, deposit, rejectionCode);
      case UNEXPLAINED -> blockUnexplained(claim, refund, order);
    };
  }

  @Transactional
  public void retry(Claim claim) {
    if (executions.ownsLease(claim)) executions.retryLater(claim);
  }

  @Transactional
  public void review(Claim claim, String reason) {
    if (!executions.ownsLease(claim)) return;
    markReview(claim, refunds.findById(claim.refundId())
        .orElseThrow(() -> new IllegalStateException("환불이 없습니다.")), reason);
  }

  private boolean succeed(Claim claim, PaymentRefund refund, PaymentDeposit deposit, TossPayment payment) {
    refund.succeed(transactionKey(payment, refund.getAmount()));
    deposit.completeRefund(refund.getAmount());
    executions.history(refund.getId(), RefundStatus.REQUESTED.name(), RefundStatus.SUCCEEDED.name(), null);
    executions.release(claim);
    return true;
  }

  private boolean fail(Claim claim, PaymentRefund refund, PaymentDeposit deposit, String code) {
    refund.fail(code);
    deposit.cancelRefund(refund.getAmount());
    executions.history(refund.getId(), RefundStatus.REQUESTED.name(), RefundStatus.FAILED.name(), code);
    executions.release(claim);
    return true;
  }

  // 장부로 설명되지 않는 PG 취소는 외부 취소와 같이 예치를 차단한다. 이 환불의 금액은 환불 예약에 남겨 운영 확인을 기다린다.
  private boolean blockUnexplained(Claim claim, PaymentRefund refund, Order order) {
    markReview(claim, refund, "UNEXPLAINED_CANCELLATION");
    if ("DEPOSITED".equals(order.status())) orders.block(order);
    return true;
  }

  private void markReview(Claim claim, PaymentRefund refund, String reason) {
    refund.requireReview(reason);
    executions.history(refund.getId(), RefundStatus.REQUESTED.name(), RefundStatus.REQUESTED.name(), reason);
    executions.release(claim);
    log.error("환불 자동 실행 중단, 운영 확인 필요: refundId={}, reason={}", refund.getId(), reason);
  }

  private boolean isSameProviderPayment(Order order, TossPayment payment) {
    return payment != null
        && order.id().equals(payment.orderId())
        && order.paymentKey() != null && order.paymentKey().equals(payment.paymentKey())
        && order.currency().equals(payment.currency())
        && payment.totalAmount() != null && order.amount().compareTo(payment.totalAmount()) == 0
        && payment.balanceAmount() != null && payment.balanceAmount().signum() >= 0
        && payment.status() != null && SETTLED_PAYMENT_STATUSES.contains(payment.status());
  }

  // 같은 금액의 완료된 취소 중 가장 최근 것. 성공 판단에는 쓰지 않는 감사용 값이다.
  private String transactionKey(TossPayment payment, BigDecimal amount) {
    for (int index = payment.cancels().size() - 1; index >= 0; index--) {
      TossPayment.Cancel cancel = payment.cancels().get(index);
      if ("DONE".equals(cancel.cancelStatus()) && cancel.cancelAmount() != null
          && cancel.cancelAmount().compareTo(amount) == 0) return cancel.transactionKey();
    }
    return null;
  }
}
