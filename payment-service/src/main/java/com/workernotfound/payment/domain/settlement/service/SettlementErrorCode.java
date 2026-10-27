package com.workernotfound.payment.domain.settlement.service;

import com.workernotfound.payment.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SettlementErrorCode implements ErrorCode {
  NOT_FOUND("SETTLEMENT-404-001", "정산을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);
  private final String code;
  private final String message;
  private final HttpStatus httpStatus;
}
