package com.workernotfound.chat.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.internal")
public record InternalApiProperties(String secret) {}
