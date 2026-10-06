package com.workernotfound.job.domain.job.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 지원 마감·근무 시작 경과 공고의 자동 상태 전이 설정. 사용 여부는 job.schedule-transition.enabled로 정한다.
@ConfigurationProperties(prefix = "job.schedule-transition")
public record JobScheduleTransitionProperties(
        Duration interval,
        Duration initialDelay,
        int batchSize
) {

    public JobScheduleTransitionProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("job.schedule-transition.interval은 0보다 커야 합니다.");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("job.schedule-transition.initial-delay는 0 이상이어야 합니다.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("job.schedule-transition.batch-size는 0보다 커야 합니다.");
        }
    }
}
