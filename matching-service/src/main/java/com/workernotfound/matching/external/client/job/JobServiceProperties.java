package com.workernotfound.matching.external.client.job;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matching.job-service")
public record JobServiceProperties(
	String baseUrl,
	Duration connectTimeout,
	Duration readTimeout,
	String internalSecret
) {
}
