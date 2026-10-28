package com.workernotfound.member.external.redis;

import com.workernotfound.member.domain.review.entity.ReviewableWork;
import com.workernotfound.member.domain.review.repository.ReviewableWorkRepository;
import com.workernotfound.member.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * work-service {@code WorkEventPublisher}가 넣는 Stream 형식을 재현해 리뷰 대상 근무 기록과 ACK·pending 규칙을 검증한다.
 */
class WorkCompletedEventConsumerTests extends IntegrationTestSupport {
	private static final AtomicLong IDS = new AtomicLong(8_100_000);
	private static final String STREAM = "work:domain-events";
	private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 10, 27, 18, 0, 0, 123_456_000);

	@Autowired
	private WorkCompletedEventConsumer consumer;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private ReviewableWorkRepository works;

	@Test
	void recordsCompletedWorkAndAcknowledgesOtherWorkEvents() {
		long workId = IDS.incrementAndGet();
		RecordId checkedIn = publish(event(workId, "WorkCheckedIn", 1, "CHECKED_IN", 20L, 30L));
		RecordId completed = publish(event(workId, "WorkCompleted", 3, "COMPLETED", 20L, 30L));

		consumer.poll();

		ReviewableWork work = works.findById(workId).orElseThrow();
		assertThat(work.getOwnerMemberId()).isEqualTo(20L);
		assertThat(work.getWorkerMemberId()).isEqualTo(30L);
		assertThat(work.getJobPostId()).isEqualTo(workId + 1);
		assertThat(work.getWorkDate()).isEqualTo(LocalDate.of(2026, 10, 27));
		assertThat(work.getCompletedAt()).isEqualTo(COMPLETED_AT);
		assertThat(pendingIds()).doesNotContain(checkedIn, completed);
	}

	@Test
	void redeliveredCompletionKeepsFirstRecord() {
		long workId = IDS.incrementAndGet();
		Map<String, String> fields = event(workId, "WorkCompleted", 3, "COMPLETED", 21L, 31L);
		RecordId first = publish(fields);
		consumer.poll();
		RecordId second = publish(fields);
		consumer.poll();

		assertThat(works.findById(workId)).isPresent();
		assertThat(works.findAll().stream().filter(work -> work.getWorkId() == workId)).hasSize(1);
		assertThat(pendingIds()).doesNotContain(first, second);
	}

	@Test
	void invalidOrConflictingCompletionStaysPendingWithoutBlockingLaterRecords() {
		long invalidWork = IDS.incrementAndGet();
		RecordId sameParties = publish(event(invalidWork, "WorkCompleted", 3, "COMPLETED", 22L, 22L));
		long conflictingWork = IDS.incrementAndGet();
		publish(event(conflictingWork, "WorkCompleted", 3, "COMPLETED", 23L, 33L));
		consumer.poll();
		Map<String, String> conflict = event(conflictingWork, "WorkCompleted", 3, "COMPLETED", 23L, 99L);
		RecordId conflicting = publish(conflict);
		long laterWork = IDS.incrementAndGet();
		RecordId later = publish(event(laterWork, "WorkCompleted", 3, "COMPLETED", 24L, 34L));

		consumer.poll();

		assertThat(works.findById(invalidWork)).isEmpty();
		assertThat(works.findById(conflictingWork)).get().extracting(ReviewableWork::getWorkerMemberId).isEqualTo(33L);
		assertThat(works.findById(laterWork)).isPresent();
		assertThat(pendingIds()).contains(sameParties, conflicting).doesNotContain(later);
	}

	@Test
	void envelopeAndPayloadMismatchStaysPending() {
		long workId = IDS.incrementAndGet();
		Map<String, String> fields = event(workId, "WorkCompleted", 3, "COMPLETED", 25L, 35L);
		fields.put("aggregateId", Long.toString(workId + 1000));
		RecordId mismatched = publish(fields);

		consumer.poll();

		assertThat(works.findById(workId)).isEmpty();
		assertThat(pendingIds()).contains(mismatched);
	}

	private Map<String, String> event(long workId, String type, long revision, String status, Long owner, Long worker) {
		String eventId = UUID.randomUUID().toString();
		String payload = """
			{"eventId":"%s","eventType":"%s","aggregateId":%d,"revision":%d,"version":1,"occurredAt":"%s",
			 "workId":%d,"matchingId":%d,"jobPostId":%d,"ownerMemberId":%d,"workerMemberId":%d,
			 "actorMemberId":%d,"actorRole":"OWNER","status":"%s","workDate":"2026-10-27",
			 "startTime":"09:00:00","endTime":"18:00:00","endTimeNextDay":false}
			""".formatted(eventId, type, workId, revision, COMPLETED_AT, workId, workId + 2, workId + 1, owner, worker,
			owner, status);
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("eventId", eventId);
		fields.put("aggregateType", "WORK");
		fields.put("aggregateId", Long.toString(workId));
		fields.put("eventType", type);
		fields.put("revision", Long.toString(revision));
		fields.put("version", "1");
		fields.put("occurredAt", COMPLETED_AT.toString());
		fields.put("payload", payload);
		return fields;
	}

	private RecordId publish(Map<String, String> fields) {
		MapRecord<String, String, String> record = StreamRecords.newRecord().in(STREAM).ofMap(fields);
		return redis.opsForStream().add(record);
	}

	private List<RecordId> pendingIds() {
		return redis.opsForStream()
			.pending(STREAM, Consumer.from(WorkCompletedEventConsumer.GROUP, WorkCompletedEventConsumer.CONSUMER),
				Range.unbounded(), 1000)
			.stream().map(PendingMessage::getId).toList();
	}
}
