package com.workernotfound.job.external.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 지원자 수 projection의 지원 상태 이벤트 Stream 소비 설정.
 *
 * <p>소비자 사용 여부는 {@code job.applicant-count.events.enabled}로 따로 켜고 끈다. 첫 실행 지연은 스케줄러가 직접 읽는다.
 */
@ConfigurationProperties(prefix = "job.applicant-count.events")
public record ApplicantCountEventConsumerProperties(
        String stream,
        int batchSize,
        Duration pollInterval
) {

    public ApplicantCountEventConsumerProperties {
        if (stream == null || stream.isBlank()) {
            throw new IllegalArgumentException("job.applicant-count.events.stream은 비어 있을 수 없습니다.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("job.applicant-count.events.batch-size는 0보다 커야 합니다.");
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("job.applicant-count.events.poll-interval은 0보다 커야 합니다.");
        }
    }
}
