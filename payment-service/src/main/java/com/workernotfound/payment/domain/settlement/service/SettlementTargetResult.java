package com.workernotfound.payment.domain.settlement.service;

/**
 * 정산 대상 기록의 확정 결과. 거절은 같은 사실로 다시 시도해도 바뀌지 않으며, DB·잠금 실패는 결과가 아니라 예외로 전파된다.
 */
public enum SettlementTargetResult {
  RECORDED,
  ALREADY_RECORDED,
  INVALID_CONFIRMATION,
  LOCK_NOT_FOUND,
  LOCK_MISMATCH,
  TARGET_CONFLICT;

  public boolean isRejected() {
    return this != RECORDED && this != ALREADY_RECORDED;
  }
}
