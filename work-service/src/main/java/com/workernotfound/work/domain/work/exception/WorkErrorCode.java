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
  CANNOT_CANCEL("WORK-409-003", "예정 근무만 Saga 보상으로 취소할 수 있습니다.", HttpStatus.CONFLICT),
  WORK_STATE_CONFLICT("WORK-409-004", "현재 근무 상태에서 처리할 수 없습니다.", HttpStatus.CONFLICT),
  LOCATION_UNAVAILABLE("WORK-409-005", "근무지 좌표가 준비되지 않았습니다.", HttpStatus.CONFLICT),
  CHECK_IN_TIME_NOT_ALLOWED("WORK-409-006", "출근 가능한 시간이 아닙니다.", HttpStatus.CONFLICT),
  CHECK_IN_TOO_FAR("WORK-409-007", "출근 인정 반경 밖입니다.", HttpStatus.CONFLICT),
  START_TOO_EARLY("WORK-409-008", "예정 근무 시작 시각 전입니다.", HttpStatus.CONFLICT),
  COMPLETION_TOO_EARLY("WORK-409-009", "예정 근무 종료 시각 전입니다.", HttpStatus.CONFLICT);
  private final String code;
  private final String message;
  private final HttpStatus httpStatus;
}
