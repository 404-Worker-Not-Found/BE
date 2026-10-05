package com.workernotfound.auth.domain.auth.service;

public class VerificationDeliveryException extends RuntimeException {
	private final boolean deliveryUncertain;
	private final String providerErrorCode;

	public VerificationDeliveryException(String message) {
		super(message);
		this.deliveryUncertain = false;
		this.providerErrorCode = null;
	}

	public VerificationDeliveryException(String message, boolean deliveryUncertain) {
		super(message);
		this.deliveryUncertain = deliveryUncertain;
		this.providerErrorCode = null;
	}

	public VerificationDeliveryException(String message, Throwable cause, boolean deliveryUncertain) {
		super(message, cause);
		this.deliveryUncertain = deliveryUncertain;
		this.providerErrorCode = null;
	}

	public VerificationDeliveryException(String message, String providerErrorCode) {
		super(message);
		this.deliveryUncertain = false;
		this.providerErrorCode = providerErrorCode;
	}

	public boolean isDeliveryUncertain() {
		return deliveryUncertain;
	}

	public String getProviderErrorCode() {
		return providerErrorCode;
	}
}
