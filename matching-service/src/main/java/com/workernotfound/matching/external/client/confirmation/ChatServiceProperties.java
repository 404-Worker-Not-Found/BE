package com.workernotfound.matching.external.client.confirmation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matching.chat-service")
public record ChatServiceProperties(
	String baseUrl,
	Duration connectTimeout,
	Duration readTimeout,
	String internalSecret
) {
}
