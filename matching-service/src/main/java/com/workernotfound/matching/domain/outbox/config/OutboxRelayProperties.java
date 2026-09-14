package com.workernotfound.matching.domain.outbox.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "matching.outbox-relay")
public record OutboxRelayProperties(
	int batchSize,
	Duration leaseDuration,
	Duration retryBaseDelay,
	Duration retryMaxDelay,
	Duration interval,
	Duration initialDelay,
	String streamKey
) {

	public OutboxRelayProperties {
		validatePositive(batchSize, "batch-size");
		validatePositive(leaseDuration, "lease-duration");
		validatePositive(retryBaseDelay, "retry-base-delay");
		validatePositive(retryMaxDelay, "retry-max-delay");
		validatePositive(interval, "interval");
		validatePositive(initialDelay, "initial-delay");
		if (retryMaxDelay.compareTo(retryBaseDelay) < 0) {
			throw new IllegalArgumentException("matching.outbox-relay.retry-max-delay는 기본 지연 이상이어야 합니다.");
		}
		if (streamKey == null || streamKey.isBlank()) {
			throw new IllegalArgumentException("matching.outbox-relay.stream-key는 필수입니다.");
		}
	}

	private static void validatePositive(int value, String name) {
		if (value <= 0) {
			throw new IllegalArgumentException("matching.outbox-relay.%s는 양수여야 합니다.".formatted(name));
		}
	}

	private static void validatePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("matching.outbox-relay.%s는 양수여야 합니다.".formatted(name));
		}
	}
}
