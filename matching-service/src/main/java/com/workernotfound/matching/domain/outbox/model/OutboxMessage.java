package com.workernotfound.matching.domain.outbox.model;

import com.workernotfound.matching.domain.outbox.entity.OutboxEvent;

public record OutboxMessage(
	Long id,
	String eventId,
	String aggregateType,
	Long aggregateId,
	String eventType,
	String correlationId,
	Long revision,
	Integer schemaVersion,
	String payload,
	Integer retryCount
) {

	public static OutboxMessage from(OutboxEvent event) {
		return new OutboxMessage(
			event.getId(),
			event.getEventId(),
			event.getAggregateType(),
			event.getAggregateId(),
			event.getEventType(),
			event.getCorrelationId(),
			event.getRevision(),
			event.getSchemaVersion(),
			event.getPayload(),
			event.getRetryCount()
		);
	}
}
