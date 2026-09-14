package com.workernotfound.matching.external.client.confirmation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matching.work-service")
public record WorkServiceProperties(
	String baseUrl,
	Duration connectTimeout,
	Duration readTimeout,
	String internalSecret
) {
}
