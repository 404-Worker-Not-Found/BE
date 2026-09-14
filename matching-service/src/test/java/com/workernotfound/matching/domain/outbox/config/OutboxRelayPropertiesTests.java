package com.workernotfound.matching.domain.outbox.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRelayPropertiesTests {

	@Test
	void acceptsValidProperties() {
		assertThatCode(() -> properties(Duration.ofSeconds(1), Duration.ofMinutes(1)))
			.doesNotThrowAnyException();
	}

	@Test
	void rejectsInvalidProperties() {
		assertThatThrownBy(() -> new OutboxRelayProperties(
			0, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(1),
			Duration.ofSeconds(1), Duration.ofSeconds(1), "stream"
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(Duration.ZERO, Duration.ofMinutes(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(Duration.ofMinutes(2), Duration.ofMinutes(1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private OutboxRelayProperties properties(Duration retryBaseDelay, Duration retryMaxDelay) {
		return new OutboxRelayProperties(
			50,
			Duration.ofSeconds(30),
			retryBaseDelay,
			retryMaxDelay,
			Duration.ofSeconds(1),
			Duration.ofSeconds(10),
			"matching:domain-events"
		);
	}
}
