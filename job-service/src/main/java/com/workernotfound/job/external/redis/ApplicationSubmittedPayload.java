package com.workernotfound.job.external.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

/**
 * matching-service {@code ApplicationEvent} v1 JSON payload 중 job-service가 확인하는 필드.
 *
 * <p>matching-service는 {@code LocalDateTime}을 숫자 배열(예: {@code [2026,10,17,10,0,0,123456789]})로 직렬화한다.
 * 상태·상관 ID처럼 쓰지 않는 필드는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ApplicationSubmittedPayload(
        String eventId,
        String eventType,
        LocalDateTime occurredAt,
        Long aggregateId,
        Long revision,
        Integer version,
        Long applicationId,
        Long admissionId,
        Long jobPostId,
        Long workerMemberId
) {
}
