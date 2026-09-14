package com.workernotfound.work.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "work.internal")
public record InternalApiProperties(String secret) {}
