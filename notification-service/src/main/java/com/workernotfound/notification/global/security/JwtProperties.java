package com.workernotfound.notification.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.jwt")
public record JwtProperties(String secret) {}
