package com.workernotfound.job.domain.job.service;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingSeatReservationPropertiesTests {

    @Test
    void rejectsTtlWithoutAWholeMicrosecond() {
        for (Duration ttl : new Duration[] {Duration.ZERO, Duration.ofNanos(999), Duration.ofSeconds(-1), null}) {
            assertThatThrownBy(() -> new MatchingSeatReservationProperties(ttl, 100))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("마이크로초");
        }
    }

    @Test
    void acceptsTtlOfAtLeastOneMicrosecond() {
        assertThat(new MatchingSeatReservationProperties(Duration.ofNanos(1_000), 100).ttl())
                .isEqualTo(Duration.ofNanos(1_000));
        assertThat(new MatchingSeatReservationProperties(Duration.ofMinutes(10).plusNanos(999), 100).ttl())
                .isEqualTo(Duration.ofMinutes(10).plusNanos(999));
    }
}
