package com.workernotfound.payment.domain.refund.service;

import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import com.workernotfound.payment.domain.payment.repository.PaymentDepositRepository;
import com.workernotfound.payment.domain.refund.repository.RefundExecutionRepository;
import com.workernotfound.payment.external.toss.TossPayment;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 재조회에서 확인한 PG 취소가 이쪽이 실행한 환불로 설명되는지 판단한다. 설명되지 않는 취소만 외부 취소로 보고 예치를 차단해야 한다.
 *
 * <p>호출자가 주문 행을 잠근 트랜잭션 안에서 예치 행을 잠근다. 환불 결과 저장도 주문 → 예치 순서로 잠그므로 두 판단이 직렬화된다.
 */
@Service
@RequiredArgsConstructor
public class RefundLedger {
  private final PaymentDepositRepository deposits;
  private final RefundExecutionRepository executions;

  @Transactional(propagation = Propagation.MANDATORY)
  public BigDecimal findRefundedAmount(Long jobPostId) {
    return deposits.findByJobPostIdForUpdate(jobPostId)
        .map(PaymentDeposit::getRefundedAmount).orElse(null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean explainsCancellation(Long jobPostId, String orderId, TossPayment payment) {
    BigDecimal canceled = RefundCoverage.canceledAmount(payment);
    if (canceled == null || canceled.signum() <= 0) return false;
    var deposit = deposits.findByJobPostIdForUpdate(jobPostId);
    if (deposit.isEmpty()) return false;
    BigDecimal refunded = deposit.get().getRefundedAmount();
    if (canceled.compareTo(refunded) == 0) return true;
    // PG에 반영됐지만 결과 저장 전인 환불이다. 환불 실행이 같은 합계로 성공을 확정한다.
    return executions.findInFlightAmount(orderId)
        .map(inFlight -> canceled.compareTo(refunded.add(inFlight)) == 0)
        .orElse(false);
  }
}
