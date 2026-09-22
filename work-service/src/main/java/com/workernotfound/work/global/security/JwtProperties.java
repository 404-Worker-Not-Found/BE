package com.workernotfound.work.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "work.jwt")
public record JwtProperties(String secret) {}
