package com.workernotfound.auth.domain.token.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
	String secret,
	Duration accessTokenExpiration,
	Duration refreshTokenExpiration
) {
}
