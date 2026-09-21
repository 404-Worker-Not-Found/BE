package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.auth.exception.AuthErrorCode;
import com.workernotfound.auth.global.exception.BusinessException;

public class SignupException extends BusinessException {
	public SignupException(AuthErrorCode errorCode) {
		super(errorCode);
	}
}
