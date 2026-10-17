package com.workernotfound.payment.domain.settlement.service;

/**
 * 정산 예약의 확정 결과. 거절은 다시 시도해도 바뀌지 않는 계약 위반이며, DB·잠금 실패는 결과가 아니라 예외로 전파된다.
 */
public record SettlementReservationResult(Outcome outcome, Long settlementId) {

  public enum Outcome {
    RESERVED,
    ALREADY_RESERVED,
    INVALID_COMPLETION,
    LOCK_NOT_FOUND,
    LOCK_MISMATCH,
    WORK_CONFLICT,
    UNSUPPORTED_AMOUNT,
    // 같은 매칭의 MatchConfirmed가 정산 대상으로 기록되지 못하고 거절됐다.
    CONFIRMATION_REJECTED
  }

  public static SettlementReservationResult rejected(Outcome outcome) {
    return new SettlementReservationResult(outcome, null);
  }

  public boolean isRejected() {
    return outcome != Outcome.RESERVED && outcome != Outcome.ALREADY_RESERVED;
  }
}
