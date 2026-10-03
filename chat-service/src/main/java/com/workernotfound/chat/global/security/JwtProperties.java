package com.workernotfound.chat.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.jwt")
public record JwtProperties(String secret) {}
