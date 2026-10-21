package com.workernotfound.payment.domain.refund.entity;

/**
 * 예치금 환불 상태. 확정된 PG 결과만 SUCCEEDED·FAILED로 옮긴다.
 *
 * <p>REQUESTED는 환불 가능액을 예약했고 PG 결과가 아직 확정되지 않은 상태다. 응답 유실·타임아웃은 REQUESTED로 남기고 같은 환불로
 * 조회·재시도한다.
 */
public enum RefundStatus {
  REQUESTED,
  SUCCEEDED,
  FAILED;

  public boolean canTransitionTo(RefundStatus next) {
    return this == REQUESTED && (next == SUCCEEDED || next == FAILED);
  }
}
