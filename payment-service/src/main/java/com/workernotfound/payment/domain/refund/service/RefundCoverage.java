package com.workernotfound.payment.domain.refund.service;

import com.workernotfound.payment.external.toss.TossPayment;
import java.math.BigDecimal;

/**
 * PG 취소 합계가 이 환불을 포함하는지 판단한다. 한 주문의 환불은 하나씩 실행하므로 앞선 환불은 모두 성공·실패로 확정돼 있고, 성공한
 * 환불 합계가 예치의 환불 완료 금액이다.
 *
 * <ul>
 *   <li>NOT_APPLIED: PG 취소 합계 = 환불 완료. 이 환불은 PG에 반영되지 않았다.
 *   <li>APPLIED: PG 취소 합계 = 환불 완료 + 이 환불 금액.
 *   <li>UNEXPLAINED: 그 밖의 합계. 이쪽 장부로 설명되지 않는 취소가 있다.
 * </ul>
 */
public enum RefundCoverage {
  NOT_APPLIED,
  APPLIED,
  UNEXPLAINED;

  public static RefundCoverage of(BigDecimal providerCanceled, BigDecimal refunded, BigDecimal amount) {
    if (providerCanceled.compareTo(refunded) == 0) return NOT_APPLIED;
    if (providerCanceled.compareTo(refunded.add(amount)) == 0) return APPLIED;
    return UNEXPLAINED;
  }

  // 토스 Payment의 누적 취소 금액. 잔액이 없으면 판단할 수 없다.
  public static BigDecimal canceledAmount(TossPayment payment) {
    if (payment.totalAmount() == null || payment.balanceAmount() == null) return null;
    return payment.totalAmount().subtract(payment.balanceAmount());
  }
}
