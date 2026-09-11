package com.workernotfound.matching.domain.application.exception;

import com.workernotfound.matching.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ApplicationErrorCode implements ErrorCode {

	APPLICATION_NOT_FOUND("APPLICATION-404-001", "존재하지 않는 지원입니다.", HttpStatus.NOT_FOUND),
	JOB_NOT_FOUND("APPLICATION-404-002", "존재하지 않는 공고입니다.", HttpStatus.NOT_FOUND),
	APPLICATION_FORBIDDEN("APPLICATION-403-001", "해당 지원에 접근할 권한이 없습니다.", HttpStatus.FORBIDDEN),
	MEMBER_NOT_ELIGIBLE("APPLICATION-403-002", "지원할 수 있는 알바생 회원이 아닙니다.", HttpStatus.FORBIDDEN),
	REAPPLICATION_NOT_ALLOWED("APPLICATION-409-001", "취소되거나 종료된 지원은 다시 접수할 수 없습니다.", HttpStatus.CONFLICT),
	APPLICATION_STATE_CONFLICT("APPLICATION-409-002", "현재 지원 상태에서는 요청을 처리할 수 없습니다.", HttpStatus.CONFLICT),
	JOB_NOT_OPEN("APPLICATION-409-003", "현재 지원을 받는 공고가 아닙니다.", HttpStatus.CONFLICT),
	APPLICATION_DEADLINE_PASSED("APPLICATION-409-004", "지원 마감 시간이 지났습니다.", HttpStatus.CONFLICT),
	ADMISSION_EXPIRED("APPLICATION-409-005", "지원 접수 승인이 만료되었습니다. 다시 시도해주세요.", HttpStatus.CONFLICT),
	DEPENDENCY_SERVICE_UNAVAILABLE("APPLICATION-503-001", "지원 자격을 확인할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE);

	private final String code;
	private final String message;
	private final HttpStatus httpStatus;
}
