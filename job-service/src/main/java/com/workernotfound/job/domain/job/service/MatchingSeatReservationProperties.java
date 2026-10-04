package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "job.matching-seat-reservation")
public record MatchingSeatReservationProperties(
        Duration ttl,
        int expirySweepBatchSize
) {

    public MatchingSeatReservationProperties {
        // 만료 시각은 마이크로초로 절삭하므로 절삭 후에도 1마이크로초 이상 남아야 유효 기간이 생긴다.
        if (ttl == null || ttl.truncatedTo(JobMatchingSeatReservation.TIME_PRECISION).compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "job.matching-seat-reservation.ttl은 마이크로초로 절삭한 뒤에도 0보다 커야 합니다.");
        }
        if (expirySweepBatchSize <= 0) {
            throw new IllegalArgumentException("job.matching-seat-reservation.expiry-sweep-batch-size는 0보다 커야 합니다.");
        }
    }
}
