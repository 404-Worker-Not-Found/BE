package com.workernotfound.matching.domain.matching.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matching.confirmation")
public record MatchingConfirmationProperties(Duration leaseDuration) {

	public MatchingConfirmationProperties {
		if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
			throw new IllegalArgumentException("matching.confirmation.lease-duration은 양수여야 합니다.");
		}
	}
}
