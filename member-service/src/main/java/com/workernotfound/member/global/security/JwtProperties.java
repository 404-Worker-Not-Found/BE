package com.workernotfound.member.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "member.jwt")
public record JwtProperties(
	String secret
) {
}
