package com.workernotfound.auth.global.security;

import com.workernotfound.auth.global.exception.BusinessException;
import com.workernotfound.auth.global.exception.GlobalErrorCode;

public class InvalidAccessTokenException extends BusinessException {
	public InvalidAccessTokenException(String message) {
		super(GlobalErrorCode.INVALID_TOKEN, message);
	}

	public InvalidAccessTokenException(String message, Throwable cause) {
		this(message);
		initCause(cause);
	}
}
