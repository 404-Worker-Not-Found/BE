package com.workernotfound.job.domain.job.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 만료 시각이 지난 RESERVED 지원 접수 승인의 만료 처리 설정. 사용 여부는 job.application-admission-expiry.enabled로 정한다.
@ConfigurationProperties(prefix = "job.application-admission-expiry")
public record JobApplicationAdmissionExpiryProperties(
        Duration interval,
        Duration initialDelay,
        int batchSize
) {

    public JobApplicationAdmissionExpiryProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("job.application-admission-expiry.interval은 0보다 커야 합니다.");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("job.application-admission-expiry.initial-delay는 0 이상이어야 합니다.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("job.application-admission-expiry.batch-size는 0보다 커야 합니다.");
        }
    }
}
