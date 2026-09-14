package com.workernotfound.matching.external.redis.outbox;

import com.workernotfound.matching.domain.outbox.config.OutboxRelayProperties;
import com.workernotfound.matching.domain.outbox.model.OutboxMessage;
import com.workernotfound.matching.domain.outbox.service.OutboxEventPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisStreamOutboxEventPublisher implements OutboxEventPublisher {

	private final StringRedisTemplate redisTemplate;
	private final OutboxRelayProperties properties;

	@Override
	public void publish(OutboxMessage message) {
		RecordId recordId = redisTemplate.opsForStream().add(
			StreamRecords.newRecord()
				.in(properties.streamKey())
				.ofMap(fields(message))
		);
		if (recordId == null) {
			throw new IllegalStateException("Redis Stream 이벤트 ID를 발급받지 못했습니다.");
		}
	}

	private Map<String, String> fields(OutboxMessage message) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("eventId", message.eventId());
		fields.put("aggregateType", message.aggregateType());
		fields.put("aggregateId", message.aggregateId().toString());
		fields.put("eventType", message.eventType());
		fields.put("correlationId", message.correlationId());
		fields.put("revision", message.revision().toString());
		fields.put("version", message.version().toString());
		fields.put("occurredAt", message.occurredAt().toString());
		fields.put("payload", message.payload());
		return fields;
	}
}
