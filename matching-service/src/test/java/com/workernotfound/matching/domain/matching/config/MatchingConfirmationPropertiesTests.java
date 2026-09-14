package com.workernotfound.matching.domain.matching.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingConfirmationPropertiesTests {

	@Test
	void acceptsPositiveLeaseDuration() {
		assertThatCode(() -> new MatchingConfirmationProperties(Duration.ofMinutes(1)))
			.doesNotThrowAnyException();
	}

	@Test
	void rejectsMissingOrNonPositiveLeaseDuration() {
		assertThatThrownBy(() -> new MatchingConfirmationProperties(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MatchingConfirmationProperties(Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MatchingConfirmationProperties(Duration.ofSeconds(-1)))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
