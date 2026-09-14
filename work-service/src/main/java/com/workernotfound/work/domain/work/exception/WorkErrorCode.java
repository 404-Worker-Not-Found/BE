package com.workernotfound.work.domain.work.exception;

import com.workernotfound.work.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum WorkErrorCode implements ErrorCode {
  WORK_NOT_FOUND("WORK-404-001", "근무를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
  COMMAND_CONFLICT("WORK-409-001", "동일 명령 키를 다른 요청에 사용할 수 없습니다.", HttpStatus.CONFLICT),
  ACTIVE_WORK_EXISTS("WORK-409-002", "매칭에 활성 근무가 이미 존재합니다.", HttpStatus.CONFLICT),
  CANNOT_CANCEL("WORK-409-003", "예정 근무만 Saga 보상으로 취소할 수 있습니다.", HttpStatus.CONFLICT);
  private final String code;
  private final String message;
  private final HttpStatus httpStatus;
}
