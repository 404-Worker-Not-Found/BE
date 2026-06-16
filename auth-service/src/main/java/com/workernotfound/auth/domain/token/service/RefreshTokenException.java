package com.workernotfound.auth.domain.token.service;

public class RefreshTokenException extends RuntimeException {

	public RefreshTokenException(String message) {
		super(message);
	}
}
