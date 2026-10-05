package com.workernotfound.auth.domain.auth.service;

public class VerificationDeliveryException extends RuntimeException {
	private final boolean deliveryUncertain;

	public VerificationDeliveryException(String message) {
		super(message);
		this.deliveryUncertain = false;
	}

	public VerificationDeliveryException(String message, boolean deliveryUncertain) {
		super(message);
		this.deliveryUncertain = deliveryUncertain;
	}

	public VerificationDeliveryException(String message, Throwable cause, boolean deliveryUncertain) {
		super(message, cause);
		this.deliveryUncertain = deliveryUncertain;
	}

	public boolean isDeliveryUncertain() {
		return deliveryUncertain;
	}
}
