package com.workernotfound.job.external.client.matching;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingServicePropertiesTests {

    private static final String BASE_URL = "http://localhost:8084";
    private static final Duration VALID = Duration.ofSeconds(1);

    @Test
    void acceptsValidTimeouts() {
        assertThatCode(() -> new MatchingServiceProperties(BASE_URL, VALID, VALID, Duration.ofSeconds(8)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect", "read", "call"})
    void rejectsMissingZeroNegativeSubMillisecondAndTooLargeTimeouts(String target) {
        for (Duration invalid : new Duration[]{
                null,
                Duration.ZERO,
                Duration.ofMillis(-1),
                Duration.of(999, ChronoUnit.MICROS),
                Duration.ofHours(1).plusMillis(1),
                Duration.ofSeconds(Long.MAX_VALUE)
        }) {
            assertThatThrownBy(() -> properties(target, invalid))
                    .as("%s=%s", target, invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(target + "-timeout");
        }
    }

    @Test
    void rejectsConnectOrHeaderTimeoutLongerThanCallTimeout() {
        assertThatThrownBy(() -> new MatchingServiceProperties(BASE_URL, Duration.ofSeconds(9), VALID, Duration.ofSeconds(8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect-timeout");
        assertThatThrownBy(() -> new MatchingServiceProperties(BASE_URL, VALID, Duration.ofSeconds(9), Duration.ofSeconds(8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-timeout");
    }

    @Test
    void rejectsNonLoopbackPlainHttpBaseUrl() {
        assertThatThrownBy(() -> new MatchingServiceProperties("http://matching.example.com", VALID, VALID, VALID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new MatchingServiceProperties("https://matching.example.com", VALID, VALID, VALID))
                .doesNotThrowAnyException();
    }

    private MatchingServiceProperties properties(String target, Duration value) {
        Duration large = Duration.ofHours(1);
        return switch (target) {
            case "connect" -> new MatchingServiceProperties(BASE_URL, value, VALID, large);
            case "read" -> new MatchingServiceProperties(BASE_URL, VALID, value, large);
            default -> new MatchingServiceProperties(BASE_URL, VALID, VALID, value);
        };
    }
}
