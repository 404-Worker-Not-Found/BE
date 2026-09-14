package com.workernotfound.matching.external.redis.outbox;

import com.workernotfound.matching.domain.outbox.config.OutboxRelayProperties;
import com.workernotfound.matching.domain.outbox.model.OutboxMessage;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStreamOutboxEventPublisherTests extends IntegrationTestSupport {

	@Autowired
	private RedisStreamOutboxEventPublisher publisher;

	@Autowired
	private OutboxRelayProperties properties;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void cleanUpRedis() {
		try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
			connection.serverCommands().flushDb();
		}
	}

	@Test
	void publishesEventEnvelopeToRedisStream() {
		publisher.publish(new OutboxMessage(
			1L, "event-id", "APPLICATION", 10L, "ApplicationSubmitted",
			"correlation-id", 1L, 1, "{\"status\":\"APPLIED\"}", 0
		));

		List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
			StreamReadOptions.empty().count(1),
			StreamOffset.fromStart(properties.streamKey())
		);

		assertThat(records).hasSize(1);
		assertThat(records.get(0).getValue())
			.containsEntry("eventId", "event-id")
			.containsEntry("aggregateType", "APPLICATION")
			.containsEntry("payload", "{\"status\":\"APPLIED\"}");
	}
}
