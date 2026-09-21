package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.auth.exception.AuthErrorCode;
import com.workernotfound.auth.global.exception.BusinessException;

public class AuthenticationException extends BusinessException {
	public AuthenticationException(AuthErrorCode errorCode) {
		super(errorCode);
	}
}
