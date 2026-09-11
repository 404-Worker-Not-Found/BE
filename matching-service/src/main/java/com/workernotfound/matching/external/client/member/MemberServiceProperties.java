package com.workernotfound.matching.external.client.member;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matching.member-service")
public record MemberServiceProperties(
	String baseUrl,
	Duration connectTimeout,
	Duration readTimeout,
	String internalSecret
) {
}
