package com.workernotfound.job.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "job.internal")
public record InternalApiProperties(
	String secret
) {
}
