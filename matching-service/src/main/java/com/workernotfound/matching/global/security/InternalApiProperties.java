package com.workernotfound.matching.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matching.internal")
public record InternalApiProperties(String secret) {
}
