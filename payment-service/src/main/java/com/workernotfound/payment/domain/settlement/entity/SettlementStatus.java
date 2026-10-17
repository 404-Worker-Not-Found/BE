package com.workernotfound.payment.domain.settlement.entity;

/**
 * 정산 상태. 지급 완료(PAID) 전까지 정산 금액은 예치의 정산 예약 항목에 남는다.
 *
 * <p>PAYOUT_REQUESTED는 외부 지급 결과를 기다리는 상태라 결과 확인 전에는 보류·재요청할 수 없다.
 */
public enum SettlementStatus {
  RESERVED,
  ON_HOLD,
  PAYOUT_REQUESTED,
  PAYOUT_FAILED,
  PAID;

  public boolean canTransitionTo(SettlementStatus next) {
    return switch (this) {
      case RESERVED -> next == ON_HOLD || next == PAYOUT_REQUESTED;
      case ON_HOLD -> next == RESERVED;
      case PAYOUT_REQUESTED -> next == PAID || next == PAYOUT_FAILED;
      case PAYOUT_FAILED -> next == PAYOUT_REQUESTED || next == ON_HOLD;
      case PAID -> false;
    };
  }
}
