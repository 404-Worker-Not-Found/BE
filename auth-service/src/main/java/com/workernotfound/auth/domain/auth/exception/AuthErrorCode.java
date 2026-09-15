package com.workernotfound.auth.domain.auth.exception;

import com.workernotfound.auth.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
	INVALID_CREDENTIALS("AUTH-401-001", "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED),
	ACCOUNT_NOT_ACTIVE("AUTH-401-002", "활성 상태의 계정만 로그인할 수 있습니다.", HttpStatus.UNAUTHORIZED),
	EMAIL_NOT_VERIFIED("AUTH-400-001", "이메일 인증이 완료되지 않았습니다.", HttpStatus.BAD_REQUEST),
	PHONE_NOT_VERIFIED("AUTH-400-002", "휴대폰 인증이 완료되지 않았습니다.", HttpStatus.BAD_REQUEST),
	INVALID_SIGNUP_TICKET("AUTH-400-003", "OAuth signup ticket이 유효하지 않습니다.", HttpStatus.BAD_REQUEST),
	EMAIL_ALREADY_EXISTS("AUTH-409-001", "이미 가입된 이메일입니다.", HttpStatus.CONFLICT),
	OAUTH_ALREADY_CONNECTED("AUTH-409-002", "이미 연결된 OAuth 계정입니다.", HttpStatus.CONFLICT),
	VERIFICATION_RATE_LIMITED(
			"AUTH-429-001", "인증번호는 1분 후 다시 요청할 수 있습니다.", HttpStatus.TOO_MANY_REQUESTS);

	private final String code;
	private final String message;
	private final HttpStatus httpStatus;
}
