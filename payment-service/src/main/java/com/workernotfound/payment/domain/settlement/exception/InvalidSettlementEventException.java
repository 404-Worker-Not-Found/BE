package com.workernotfound.payment.domain.settlement.exception;

/**
 * 해석할 수 없거나 계약을 어긴 정산 소비 대상 이벤트.
 *
 * <p>envelope·payload 불일치, 지원하지 않는 스키마 버전, 같은 eventId의 다른 내용처럼 재시도로 해결되지 않지만 처리 결과로 기록할 수도
 * 없는 경우다. 기존 이벤트 소비자 규칙대로 수신 기록 없이 롤백하고 ACK하지 않아 pending으로 남긴다.
 */
public class InvalidSettlementEventException extends RuntimeException {
  public InvalidSettlementEventException(String message) {
    super(message);
  }

  public InvalidSettlementEventException(String message, Throwable cause) {
    super(message, cause);
  }
}
