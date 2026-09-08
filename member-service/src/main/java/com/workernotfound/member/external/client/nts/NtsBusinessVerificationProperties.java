package com.workernotfound.member.external.client.nts;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "member.business-verification")
public record NtsBusinessVerificationProperties(
	String baseUrl,
	String serviceKey,
	Duration connectTimeout,
	Duration readTimeout
) {
}
