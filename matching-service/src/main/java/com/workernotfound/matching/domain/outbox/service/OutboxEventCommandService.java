package com.workernotfound.matching.domain.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.matching.domain.application.event.ApplicationEvent;
import com.workernotfound.matching.domain.outbox.entity.OutboxEvent;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OutboxEventCommandService {

	private static final String APPLICATION_AGGREGATE = "APPLICATION";

	private final OutboxEventRepository outboxEventRepository;
	private final ObjectMapper objectMapper;

	public OutboxEvent saveApplicationEvent(ApplicationEvent event) {
		OutboxEvent outboxEvent = OutboxEvent.builder()
			.eventId(event.eventId())
			.aggregateType(APPLICATION_AGGREGATE)
			.aggregateId(event.aggregateId())
			.eventType(event.eventType())
			.correlationId(event.correlationId())
			.revision(event.revision())
			.schemaVersion(event.version())
			.payload(toJson(event))
			.occurredAt(event.occurredAt())
			.build();
		return outboxEventRepository.saveAndFlush(outboxEvent);
	}

	private String toJson(ApplicationEvent event) {
		try {
			return objectMapper.writeValueAsString(event);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("지원 이벤트 payload 직렬화에 실패했습니다.", exception);
		}
	}
}
