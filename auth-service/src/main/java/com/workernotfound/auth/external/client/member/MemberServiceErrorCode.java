package com.workernotfound.auth.external.client.member;

import com.workernotfound.auth.global.exception.ErrorCode;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
@RequiredArgsConstructor
public enum MemberServiceErrorCode implements ErrorCode {
	EMAIL_ALREADY_EXISTS("MEMBER-409-001", "이미 사용 중인 이메일입니다.", HttpStatus.CONFLICT),
	PHONE_ALREADY_EXISTS("MEMBER-409-002", "이미 사용 중인 휴대폰 번호입니다.", HttpStatus.CONFLICT),
	ROLE_MISMATCH("MEMBER-400-001", "회원 역할이 요청한 가입 유형과 일치하지 않습니다.", HttpStatus.BAD_REQUEST),
	DUPLICATE_BUSINESS_TYPE("WORKER-400-001", "선호 업종이 중복되었습니다.", HttpStatus.BAD_REQUEST),
	INVALID_AVAILABLE_TIME("WORKER-400-002", "근무 시작 시간은 종료 시간보다 빨라야 합니다.", HttpStatus.BAD_REQUEST),

	INVALID_BUSINESS_REGISTRATION_NUMBER(
			"OWNER-400-001", "사업자등록번호 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
	BUSINESS_NOT_OPERATING("OWNER-400-002", "현재 영업 중인 사업자등록번호가 아닙니다.", HttpStatus.BAD_REQUEST),
	BUSINESS_VERIFICATION_UNAVAILABLE(
			"OWNER-503-001", "사업자등록 상태를 확인할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE);

	private final String code;
	private final String message;
	private final HttpStatus httpStatus;

	public static MemberServiceErrorCode find(HttpStatusCode statusCode, String code) {
		return Arrays.stream(values())
				.filter(errorCode -> errorCode.httpStatus.value() == statusCode.value())
				.filter(errorCode -> errorCode.code.equals(code))
				.findFirst()
				.orElse(null);
	}
}
