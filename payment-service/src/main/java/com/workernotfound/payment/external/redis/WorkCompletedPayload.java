package com.workernotfound.payment.external.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

/**
 * work-service {@code WorkLifecycleEvent} v1 JSON payload 중 정산에 쓰는 필드. 처리자·일정 필드는 읽지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record WorkCompletedPayload(String eventId, String eventType, Long aggregateId, Long revision, Integer version,
    LocalDateTime occurredAt, Long workId, Long matchingId, Long jobPostId, Long ownerMemberId,
    Long workerMemberId, String status) {}
