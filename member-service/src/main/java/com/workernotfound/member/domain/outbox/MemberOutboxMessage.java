package com.workernotfound.member.domain.outbox;

import java.time.LocalDateTime;

public record MemberOutboxMessage(long id, String eventId, String aggregateType, long aggregateId, String eventType,
	long revision, int version, LocalDateTime occurredAt, String payload, int retryCount) {
}
