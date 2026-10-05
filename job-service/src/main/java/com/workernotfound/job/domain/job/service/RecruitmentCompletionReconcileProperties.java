package com.workernotfound.job.domain.job.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 정원이 확정됐지만 마감되지 않은 공고 복구 설정. 사용 여부는 job.recruitment-completion.reconcile.enabled로 정한다.
@ConfigurationProperties(prefix = "job.recruitment-completion.reconcile")
public record RecruitmentCompletionReconcileProperties(
        Duration interval,
        Duration initialDelay,
        int batchSize
) {

    public RecruitmentCompletionReconcileProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("job.recruitment-completion.reconcile.interval은 0보다 커야 합니다.");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("job.recruitment-completion.reconcile.initial-delay는 0 이상이어야 합니다.");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("job.recruitment-completion.reconcile.batch-size는 0보다 커야 합니다.");
        }
    }
}
