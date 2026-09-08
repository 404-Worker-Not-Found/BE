package com.workernotfound.member.domain.owner.exception;

import com.workernotfound.member.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OwnerErrorCode implements ErrorCode {

	INVALID_BUSINESS_REGISTRATION_NUMBER(
		"OWNER-400-001",
		"사업자등록번호 형식이 올바르지 않습니다.",
		HttpStatus.BAD_REQUEST
	),
	BUSINESS_NOT_OPERATING(
		"OWNER-400-002",
		"현재 영업 중인 사업자등록번호가 아닙니다.",
		HttpStatus.BAD_REQUEST
	),
	BUSINESS_VERIFICATION_UNAVAILABLE(
		"OWNER-503-001",
		"사업자등록 상태를 확인할 수 없습니다. 잠시 후 다시 시도해주세요.",
		HttpStatus.SERVICE_UNAVAILABLE
	);

	private final String code;
	private final String message;
	private final HttpStatus httpStatus;
}
