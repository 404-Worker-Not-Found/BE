package com.workernotfound.member.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "member.internal")
public record InternalApiProperties(
	String secret
) {
}
