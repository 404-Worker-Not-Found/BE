package com.workernotfound.matching.external.redis;

import com.workernotfound.matching.domain.score.service.TrustScoreProjectionService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code member:domain-events}의 TrustScoreUpdated를 matching 전용 그룹으로 소비해 신뢰 점수 사본을 갱신한다.
 * 다른 이벤트는 바로 ACK하고, 반영·중복·역순(더 작은 revision)은 커밋 뒤 ACK한다. 해석할 수 없는 레코드와 일시 오류는
 * pending으로 남겨 매 poll마다 회전 커서로 다시 읽는다. 그룹은 0-0부터 만들어 소비자 연결 전 이벤트도 반영한다.
 */
@Component
@EnableScheduling
@Slf4j
@ConditionalOnProperty(name = "matching.member-events.enabled", havingValue = "true", matchIfMissing = true)
public class TrustScoreEventConsumer {
	static final String GROUP = "matching-trust-score-v1";
	static final String CONSUMER = "matching-trust-score";
	private static final String FIRST_ID = "0-0";
	private static final int BATCH_SIZE = 100;

	private final StringRedisTemplate redis;
	private final TrustScoreEventParser parser;
	private final TrustScoreProjectionService service;
	private final String stream;
	private String pendingCursor = FIRST_ID;

	public TrustScoreEventConsumer(StringRedisTemplate redis, TrustScoreEventParser parser,
		TrustScoreProjectionService service,
		@Value("${matching.member-events.stream:member:domain-events}") String stream) {
		this.redis = redis;
		this.parser = parser;
		this.service = service;
		this.stream = stream;
	}

	@Scheduled(fixedDelayString = "${matching.member-events.poll-delay-ms:1000}",
		initialDelayString = "${matching.member-events.initial-delay-ms:0}")
	public synchronized void poll() {
		try {
			ensureGroup();
			List<MapRecord<String, Object, Object>> pending = read(ReadOffset.from(pendingCursor));
			pendingCursor = pending.isEmpty() ? FIRST_ID : pending.get(pending.size() - 1).getId().getValue();
			process(pending);
			process(read(ReadOffset.lastConsumed()));
		} catch (RuntimeException exception) {
			log.warn("신뢰 점수 이벤트 poll 재시도: type={}", exception.getClass().getSimpleName());
		}
	}

	private void ensureGroup() {
		try {
			redis.execute((RedisCallback<Object>) connection -> connection.execute("XGROUP", bytes("CREATE"),
				bytes(stream), bytes(GROUP), bytes(FIRST_ID), bytes("MKSTREAM")));
		} catch (RuntimeException exception) {
			for (Throwable cause = exception; cause != null; cause = cause.getCause())
				if (cause.getMessage() != null && cause.getMessage().startsWith("BUSYGROUP")) return;
			throw exception;
		}
	}

	private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
		List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(Consumer.from(GROUP, CONSUMER),
			StreamReadOptions.empty().count(BATCH_SIZE), StreamOffset.create(stream, offset));
		return records == null ? List.of() : records;
	}

	private void process(List<MapRecord<String, Object, Object>> records) {
		for (MapRecord<String, Object, Object> record : records) {
			try {
				if (parser.isTarget(record.getValue())) service.receive(parser.parse(record.getValue()));
				redis.opsForStream().acknowledge(stream, GROUP, record.getId());
			} catch (RuntimeException exception) {
				log.warn("신뢰 점수 이벤트 pending 유지: recordId={}, type={}", record.getId(),
					exception.getClass().getSimpleName());
			}
		}
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
