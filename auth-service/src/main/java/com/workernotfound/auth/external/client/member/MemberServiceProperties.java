package com.workernotfound.auth.external.client.member;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.member-service")
public record MemberServiceProperties(
	String baseUrl,
	Duration connectTimeout,
	Duration readTimeout
) {
}
