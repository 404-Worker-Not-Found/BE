package com.workernotfound.auth.global.exception;

import java.util.Map;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;
	private final Map<String, Object> reasons;

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, errorCode.getMessage(), null);
	}

	public BusinessException(ErrorCode errorCode, String message) {
		this(errorCode, message, null);
	}

	public BusinessException(ErrorCode errorCode, String message, Map<String, Object> reasons) {
		super(message);
		this.errorCode = errorCode;
		this.reasons = reasons;
	}
}
