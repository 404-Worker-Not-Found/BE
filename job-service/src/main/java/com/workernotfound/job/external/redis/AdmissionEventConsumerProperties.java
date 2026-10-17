package com.workernotfound.job.external.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code ApplicationSubmitted} Stream 소비 설정.
 *
 * <p>소비자 사용 여부는 {@code job.admission-events.enabled}로 따로 켜고 끈다. 첫 실행 지연은 스케줄러가 직접 읽는다.
 */
@ConfigurationProperties(prefix = "job.admission-events")
public record AdmissionEventConsumerProperties(
        String stream,
        int batchSize,
        Duration pollInterval
) {

    public AdmissionEventConsumerProperties {
        if (stream == null || stream.isBlank()) {
            throw new IllegalArgumentException("job.admission-events.stream은 비어 있을 수 없습니다.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("job.admission-events.batch-size는 0보다 커야 합니다.");
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("job.admission-events.poll-interval은 0보다 커야 합니다.");
        }
    }
}
