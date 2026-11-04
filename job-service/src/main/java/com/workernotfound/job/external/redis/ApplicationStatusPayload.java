package com.workernotfound.job.external.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * matching-service {@code ApplicationEvent} v1 JSON payload 중 지원자 수 projection이 확인하는 필드.
 *
 * <p>발생 시각·승인 ID·상관 ID처럼 순서 판단에 쓰지 않는 필드는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ApplicationStatusPayload(
        String eventId,
        String eventType,
        Long aggregateId,
        Long revision,
        Integer version,
        Long applicationId,
        Long jobPostId,
        String status
) {
}
