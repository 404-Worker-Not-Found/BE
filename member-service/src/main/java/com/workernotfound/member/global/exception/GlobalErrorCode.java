package com.workernotfound.member.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum GlobalErrorCode implements ErrorCode {

	INVALID_REQUEST("GLOBAL-400-001", "잘못된 요청입니다.", HttpStatus.BAD_REQUEST),
	VALIDATION_ERROR("GLOBAL-400-002", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
	UNAUTHORIZED("GLOBAL-401-001", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),
	FORBIDDEN("GLOBAL-403-001", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN),
	NOT_FOUND("GLOBAL-404-001", "요청한 리소스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
	DUPLICATE_RESOURCE("GLOBAL-409-001", "이미 존재하는 리소스입니다.", HttpStatus.CONFLICT),
	INTERNAL_SERVER_ERROR("GLOBAL-500-001", "서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

	private final String code;
	private final String message;
	private final HttpStatus httpStatus;
}
