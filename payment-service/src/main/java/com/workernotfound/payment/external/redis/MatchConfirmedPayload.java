package com.workernotfound.payment.external.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * matching-service {@code MatchingEvent}의 MatchConfirmed v1 JSON payload 중 정산 대상에 쓰는 필드. workId·paymentId는
 * 발행 형식대로 문자열이다. 지원 ID·채팅방·일정 등 나머지 필드는 읽지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record MatchConfirmedPayload(String eventId, String eventType, LocalDateTime occurredAt, Long aggregateId,
    Long revision, Integer version, Long matchingId, Long jobPostId, Long ownerMemberId, Long workerMemberId,
    String workId, String paymentId, BigDecimal lockedAmount, String currency) {}
