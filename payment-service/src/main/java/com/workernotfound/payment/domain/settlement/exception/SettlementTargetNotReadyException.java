package com.workernotfound.payment.domain.settlement.exception;

/**
 * 근무 완료를 받았지만 그 매칭의 MatchConfirmed가 아직 정산 대상으로 기록되지 않았다.
 *
 * <p>matching·work Stream은 따로 전달되므로 근무 완료가 먼저 올 수 있다. 나중에 정상화될 수 있는 상태라 거절로 기록하지 않고
 * 롤백한다. 소비자는 ACK하지 않고 pending 재수신으로 다시 처리한다.
 */
public class SettlementTargetNotReadyException extends RuntimeException {
  public SettlementTargetNotReadyException(String message) {
    super(message);
  }
}
