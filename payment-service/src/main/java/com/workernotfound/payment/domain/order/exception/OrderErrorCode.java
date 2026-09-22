package com.workernotfound.payment.domain.order.exception;

import com.workernotfound.payment.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
  NOT_FOUND("ORDER-404-001", "결제 주문을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
  CONFLICT("ORDER-409-001", "현재 주문 상태 또는 요청 내용과 일치하지 않습니다.", HttpStatus.CONFLICT),
  BUSY("ORDER-409-002", "결제를 확인 중입니다. 같은 요청으로 다시 확인해주세요.", HttpStatus.CONFLICT),
  PROVIDER_UNAVAILABLE(
      "ORDER-503-001", "결제 결과를 확인하지 못했습니다. 잠시 후 다시 확인해주세요.", HttpStatus.SERVICE_UNAVAILABLE),
  PROVIDER_MISMATCH("ORDER-502-001", "결제 검증 결과가 주문과 일치하지 않습니다.", HttpStatus.BAD_GATEWAY);
  private final String code;
  private final String message;
  private final HttpStatus httpStatus;
}
