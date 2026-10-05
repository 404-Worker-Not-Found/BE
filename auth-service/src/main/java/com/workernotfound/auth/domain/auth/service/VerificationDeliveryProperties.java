package com.workernotfound.auth.domain.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.verification.delivery")
public record VerificationDeliveryProperties(
	String resendApiKey,
	String emailFrom,
	String solapiApiKey,
	String solapiApiSecret,
	String smsFrom
) {
}
