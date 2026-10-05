package com.workernotfound.auth.domain.auth.service;

public class VerificationDeliveryException extends RuntimeException {
	public VerificationDeliveryException(String message) {
		super(message);
	}

	public VerificationDeliveryException(String message, Throwable cause) {
		super(message, cause);
	}
}
