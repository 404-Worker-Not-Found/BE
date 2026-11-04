package com.workernotfound.job.domain.job.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 지원자 수 projection 초기 적재·정합성 비교 설정. 사용 여부는 job.applicant-count.sync.enabled로 정한다.
@ConfigurationProperties(prefix = "job.applicant-count.sync")
public record ApplicantCountSyncProperties(
        Duration interval,
        Duration initialDelay,
        int batchSize
) {

    public ApplicantCountSyncProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("job.applicant-count.sync.interval은 0보다 커야 합니다.");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("job.applicant-count.sync.initial-delay는 0 이상이어야 합니다.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("job.applicant-count.sync.batch-size는 0보다 커야 합니다.");
        }
    }
}
