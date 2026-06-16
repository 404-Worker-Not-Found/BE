package com.workernotfound.auth.domain.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.verification")
public record VerificationProperties(
	boolean logCodeEnabled
) {
}
