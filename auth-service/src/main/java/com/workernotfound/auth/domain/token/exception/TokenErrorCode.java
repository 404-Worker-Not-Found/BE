package com.workernotfound.auth.domain.token.exception;

import com.workernotfound.auth.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum TokenErrorCode implements ErrorCode {
	REFRESH_TOKEN_NOT_FOUND("TOKEN-401-001", "refresh token을 찾을 수 없습니다.", HttpStatus.UNAUTHORIZED),
	REFRESH_TOKEN_REVOKED("TOKEN-401-002", "이미 폐기된 refresh token입니다.", HttpStatus.UNAUTHORIZED),
	REFRESH_TOKEN_EXPIRED("TOKEN-401-003", "만료된 refresh token입니다.", HttpStatus.UNAUTHORIZED),
	ACCOUNT_NOT_ACTIVE("TOKEN-401-004", "활성 상태의 계정만 token을 재발급할 수 있습니다.", HttpStatus.UNAUTHORIZED),
	DEVICE_MISMATCH("TOKEN-401-005", "refresh token의 deviceId가 일치하지 않습니다.", HttpStatus.UNAUTHORIZED);

	private final String code;
	private final String message;
	private final HttpStatus httpStatus;
}
