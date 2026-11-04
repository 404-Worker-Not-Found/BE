package com.workernotfound.job.domain.job.port;

import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;

// matching-service 공고별 지원 상태 스냅샷의 한 행
public record ApplicationStateSnapshot(
        Long applicationId,
        ApplicationStatus status,
        Long revision
) {
}
