package com.workernotfound.job.domain.job.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "job.matching-seat-reservation")
public record MatchingSeatReservationProperties(
        Duration ttl,
        int expirySweepBatchSize
) {

    public MatchingSeatReservationProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("job.matching-seat-reservation.ttl은 0보다 커야 합니다.");
        }
        if (expirySweepBatchSize <= 0) {
            throw new IllegalArgumentException("job.matching-seat-reservation.expiry-sweep-batch-size는 0보다 커야 합니다.");
        }
    }
}
