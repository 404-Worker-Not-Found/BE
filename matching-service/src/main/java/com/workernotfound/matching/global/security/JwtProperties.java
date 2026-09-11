package com.workernotfound.matching.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matching.jwt")
public record JwtProperties(
	String secret
) {
}
