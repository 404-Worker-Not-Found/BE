package com.workernotfound.payment.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.internal")
public record InternalApiProperties(String secret) {}
