package com.workernotfound.member.external.redis;

import com.workernotfound.member.domain.review.service.NoShowPenaltyService;
import com.workernotfound.member.domain.review.service.NoShowPenaltyService.Outcome;
import com.workernotfound.member.domain.review.service.WorkNoShow;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code work:domain-events}의 WorkNoShow를 신뢰 점수 전용 그룹으로 소비해 근로자의 노쇼를 감점한다.
 *
 * <p>ACK 규칙은 {@link WorkCompletedEventConsumer}와 같다. 대상이 아닌 이벤트는 바로 ACK하고, 기록·중복은 커밋 뒤 ACK한다.
 * 해석할 수 없는 레코드와 같은 근무 ID의 다른 내용, 일시 오류는 pending으로 남겨 매 poll마다 회전 커서로 다시 읽는다.
 * 리뷰 대상 근무 그룹과 분리했으므로 그룹을 처음 만들 때 Stream 처음부터 읽어 배포 전 노쇼도 반영한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "member.no-show-events.enabled", havingValue = "true", matchIfMissing = true)
public class WorkNoShowEventConsumer {
	static final String GROUP = "member-trust-no-show-v1";
	static final String CONSUMER = "member-trust-no-show";
	private static final String FIRST_ID = "0-0";

	private final StringRedisTemplate redis;
	private final WorkNoShowEventParser parser;
	private final NoShowPenaltyService penalties;
	private final String stream;
	private final int batchSize;
	private String pendingCursor = FIRST_ID;

	public WorkNoShowEventConsumer(StringRedisTemplate redis, WorkNoShowEventParser parser,
		NoShowPenaltyService penalties, NoShowEventProperties properties) {
		this.redis = redis;
		this.parser = parser;
		this.penalties = penalties;
		this.stream = properties.workStream();
		this.batchSize = properties.batchSize();
	}

	@Scheduled(fixedDelayString = "${member.no-show-events.poll-interval}",
		initialDelayString = "${member.no-show-events.initial-delay}")
	public synchronized void poll() {
		try {
			ensureGroup();
			List<MapRecord<String, Object, Object>> pending = read(ReadOffset.from(pendingCursor));
			pendingCursor = pending.isEmpty() ? FIRST_ID : pending.get(pending.size() - 1).getId().getValue();
			pending.forEach(this::handle);
			read(ReadOffset.lastConsumed()).forEach(this::handle);
		} catch (RuntimeException exception) {
			log.warn("노쇼 근무 이벤트 poll 실패: stream={}, type={}", stream, exception.getClass().getName());
		}
	}

	private void handle(MapRecord<String, Object, Object> record) {
		try {
			if (parser.isTarget(record.getValue())) process(record);
			// 처리 트랜잭션이 커밋된 뒤에만 ACK한다. 다른 서비스 그룹에 영향을 주지 않도록 Stream은 trim하지 않는다.
			redis.opsForStream().acknowledge(stream, GROUP, record.getId());
		} catch (InvalidWorkEventException exception) {
			log.error("해석할 수 없는 노쇼 근무 이벤트를 pending으로 남깁니다: stream={}, recordId={}, reason={}", stream,
				record.getId(), exception.getMessage());
		} catch (RuntimeException exception) {
			log.warn("노쇼 근무 이벤트 처리를 재시도하도록 pending으로 남깁니다: stream={}, recordId={}, type={}", stream,
				record.getId(), exception.getClass().getName());
		}
	}

	private void process(MapRecord<String, Object, Object> record) {
		WorkNoShow noShow = parser.parse(record.getValue());
		if (penalties.receive(noShow) == Outcome.CONFLICT)
			throw new InvalidWorkEventException("같은 근무 ID로 다른 공고·참여자의 노쇼가 들어왔습니다: workId="
				+ noShow.workId());
	}

	private void ensureGroup() {
		try {
			redis.execute((RedisCallback<Object>) connection -> create(connection));
		} catch (RuntimeException exception) {
			if (!isBusyGroup(exception)) throw exception;
		}
	}

	private Object create(RedisConnection connection) {
		return connection.execute("XGROUP", bytes("CREATE"), bytes(stream), bytes(GROUP), bytes(FIRST_ID),
			bytes("MKSTREAM"));
	}

	private boolean isBusyGroup(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause.getMessage() != null && cause.getMessage().startsWith("BUSYGROUP")) return true;
		}
		return false;
	}

	private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
		List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
			Consumer.from(GROUP, CONSUMER), StreamReadOptions.empty().count(batchSize),
			StreamOffset.create(stream, offset));
		return records == null ? List.of() : records;
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
