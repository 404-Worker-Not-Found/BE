package com.workernotfound.payment.domain.payment.exception;

import com.workernotfound.payment.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
  FUNDING_BLOCKED("PAYMENT-409-006", "예치 결제 상태 확인이 필요합니다.", HttpStatus.CONFLICT),
  PAYMENT_NOT_FOUND("PAYMENT-404-001", "결제 잠금을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
  DEPOSIT_NOT_FOUND("PAYMENT-409-001", "검증된 예치 금액이 없습니다.", HttpStatus.CONFLICT),
  COMMAND_CONFLICT("PAYMENT-409-002", "동일 명령 키를 다른 요청에 사용할 수 없습니다.", HttpStatus.CONFLICT),
  ACTIVE_LOCK_EXISTS("PAYMENT-409-003", "매칭에 활성 결제 잠금이 이미 존재합니다.", HttpStatus.CONFLICT),
  INSUFFICIENT_DEPOSIT("PAYMENT-409-004", "예치 잔액이 부족합니다.", HttpStatus.CONFLICT),
  DEPOSIT_MISMATCH("PAYMENT-409-005", "예치 소유자 또는 통화가 일치하지 않습니다.", HttpStatus.CONFLICT);
  private final String code;
  private final String message;
  private final HttpStatus httpStatus;
}
