package com.workernotfound.auth.domain.token.service;

import com.workernotfound.auth.domain.token.exception.TokenErrorCode;
import com.workernotfound.auth.global.exception.BusinessException;

public class RefreshTokenException extends BusinessException {
	public RefreshTokenException(TokenErrorCode errorCode) {
		super(errorCode);
	}
}
