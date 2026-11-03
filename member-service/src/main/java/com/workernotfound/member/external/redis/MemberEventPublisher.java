package com.workernotfound.member.external.redis;

import com.workernotfound.member.domain.outbox.MemberOutboxMessage;
import com.workernotfound.member.domain.outbox.MemberOutboxProperties;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 다른 서비스 이벤트와 같은 공통 envelope(eventId, aggregateType, aggregateId, eventType, revision, version, occurredAt, payload)로 기록한다.
@Component
@RequiredArgsConstructor
public class MemberEventPublisher {
	private final StringRedisTemplate redis;
	private final MemberOutboxProperties properties;

	public void publish(MemberOutboxMessage message) {
		Map<String, String> fields = Map.of("eventId", message.eventId(), "aggregateType", message.aggregateType(),
			"aggregateId", Long.toString(message.aggregateId()), "eventType", message.eventType(),
			"revision", Long.toString(message.revision()), "version", Integer.toString(message.version()),
			"occurredAt", message.occurredAt().toString(), "payload", message.payload());
		if (redis.opsForStream().add(StreamRecords.newRecord().in(properties.stream()).ofMap(fields)) == null)
			throw new IllegalStateException("회원 이벤트의 Redis Stream ID를 발급받지 못했습니다.");
	}
}
