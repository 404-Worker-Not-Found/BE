package com.workernotfound.member.external.redis;

import com.workernotfound.member.domain.review.service.ReviewableWorkReceiptService;
import com.workernotfound.member.domain.review.service.ReviewableWorkReceiptService.Outcome;
import com.workernotfound.member.domain.review.service.WorkCompletion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
 * {@code work:domain-events}의 WorkCompleted를 member-service 전용 그룹으로 소비해 리뷰할 수 있는 근무를 기록한다.
 *
 * <p>ACK 규칙은 다른 서비스의 근무·매칭 이벤트 소비자와 같다.
 * <ul>
 *   <li>대상이 아닌 이벤트 종류·aggregateType(출근·시작 등)은 바로 ACK한다.</li>
 *   <li>기록·중복은 커밋된 뒤 ACK한다.</li>
 *   <li>해석할 수 없는 레코드와 같은 근무 ID의 다른 내용은 기록하지 않고 오류 로그와 함께 pending으로 남긴다. 자동 ACK나 재시도
 *   한도는 두지 않는다. DB 장애 같은 일시 오류와 ACK 실패도 pending으로 남아 재처리한다.</li>
 * </ul>
 *
 * <p>pending은 매 poll마다 회전 커서로 batch-size만큼 다시 읽은 뒤 새 레코드를 읽는다. 여러 인스턴스는 하나의 논리 소비자를
 * 공유하고 동시 재전달은 MySQL 기본 키가 직렬화한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "member.review-events.enabled", havingValue = "true", matchIfMissing = true)
public class WorkCompletedEventConsumer {
	static final String GROUP = "member-review-work-v1";
	static final String CONSUMER = "member-review-work";
	private static final String FIRST_ID = "0-0";

	private final StringRedisTemplate redis;
	private final WorkCompletedEventParser parser;
	private final ReviewableWorkReceiptService receipts;
	private final String stream;
	private final int batchSize;
	private String pendingCursor = FIRST_ID;

	public WorkCompletedEventConsumer(StringRedisTemplate redis, WorkCompletedEventParser parser,
		ReviewableWorkReceiptService receipts, ReviewEventProperties properties) {
		this.redis = redis;
		this.parser = parser;
		this.receipts = receipts;
		this.stream = properties.workStream();
		this.batchSize = properties.batchSize();
	}

	@Scheduled(fixedDelayString = "${member.review-events.poll-interval}",
		initialDelayString = "${member.review-events.initial-delay}")
	public synchronized void poll() {
		try {
			ensureGroup();
			List<MapRecord<String, Object, Object>> pending = read(ReadOffset.from(pendingCursor));
			pendingCursor = pending.isEmpty() ? FIRST_ID : pending.get(pending.size() - 1).getId().getValue();
			pending.forEach(this::handle);
			read(ReadOffset.lastConsumed()).forEach(this::handle);
		} catch (RuntimeException exception) {
			log.warn("리뷰 대상 근무 이벤트 poll 실패: stream={}, type={}", stream, exception.getClass().getName());
		}
	}

	private void handle(MapRecord<String, Object, Object> record) {
		try {
			if (parser.isTarget(record.getValue())) process(record);
			// 처리 트랜잭션이 커밋된 뒤에만 ACK한다. 다른 서비스 그룹에 영향을 주지 않도록 Stream은 trim하지 않는다.
			redis.opsForStream().acknowledge(stream, GROUP, record.getId());
		} catch (InvalidReviewEventException exception) {
			log.error("해석할 수 없는 근무 이벤트를 pending으로 남깁니다: stream={}, recordId={}, reason={}", stream,
				record.getId(), exception.getMessage());
		} catch (RuntimeException exception) {
			log.warn("근무 이벤트 처리를 재시도하도록 pending으로 남깁니다: stream={}, recordId={}, type={}", stream,
				record.getId(), exception.getClass().getName());
		}
	}

	private void process(MapRecord<String, Object, Object> record) {
		WorkCompletion completion = parser.parse(record.getValue());
		if (receipts.receive(completion) == Outcome.CONFLICT)
			throw new InvalidReviewEventException("같은 근무 ID로 다른 공고·참여자의 완료가 들어왔습니다: workId="
				+ completion.workId());
	}

	// 그룹이 없으면 Stream 처음부터 읽도록 만든다. 소비자 연결 전에 발행된 완료 근무도 리뷰 대상이 된다.
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
