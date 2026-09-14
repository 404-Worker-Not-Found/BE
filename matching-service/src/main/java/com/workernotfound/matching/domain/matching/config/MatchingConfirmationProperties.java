package com.workernotfound.matching.domain.matching.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matching.confirmation")
public record MatchingConfirmationProperties(Duration leaseDuration) {
}
