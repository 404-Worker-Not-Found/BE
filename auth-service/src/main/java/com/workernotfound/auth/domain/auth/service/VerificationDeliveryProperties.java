package com.workernotfound.auth.domain.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.verification.delivery")
public record VerificationDeliveryProperties(
	String emailUsername,
	String emailPassword,
	String solapiApiKey,
	String solapiApiSecret,
	String smsFrom
) {
}
