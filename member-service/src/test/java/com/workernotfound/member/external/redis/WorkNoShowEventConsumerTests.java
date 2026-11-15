package com.workernotfound.member.external.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.member.domain.review.dto.request.WriteReviewRequest;
import com.workernotfound.member.domain.review.repository.MemberTrustScoreRepository;
import com.workernotfound.member.domain.review.repository.MemberTrustScoreRepository.CurrentTrustScore;
import com.workernotfound.member.domain.review.service.NoShowPenaltyService;
import com.workernotfound.member.domain.review.service.ReviewCommandService;
import com.workernotfound.member.domain.review.service.ReviewableWorkReceiptService;
import com.workernotfound.member.domain.review.service.WorkCompletion;
import com.workernotfound.member.domain.review.service.WorkNoShow;
import com.workernotfound.member.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * work-service {@code WorkEventPublisher}가 넣는 WorkNoShow Stream 형식을 재현해 노쇼 감점, 중복 제거, ACK·pending 규칙과
 * 리뷰와의 합산을 검증한다. 기본 설정은 기본 평점 3.0, 보정 리뷰 3개, 노쇼 1회당 10점 감점이다.
 */
class WorkNoShowEventConsumerTests extends IntegrationTestSupport {
	private static final AtomicLong IDS = new AtomicLong(8_900_000);
	private static final String STREAM = "work:domain-events";
	private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime NO_SHOW_AT = LocalDateTime.of(2026, 11, 3, 9, 31, 0, 123_456_789);

	@Autowired
	private WorkNoShowEventConsumer consumer;

	@Autowired
	private ReviewCommandService reviews;

	@Autowired
	private ReviewableWorkReceiptService reviewableWorks;

	@Autowired
	private NoShowPenaltyService penalties;

	@Autowired
	private MemberTrustScoreRepository trustScores;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void noShowWithoutReviewsStartsFromNeutralScoreAndAcknowledgesOtherEvents() throws Exception {
		long owner = IDS.incrementAndGet();
		long worker = IDS.incrementAndGet();
		long workId = IDS.incrementAndGet();
		RecordId completed = publish(event(workId - 100_000, "WorkCompleted", 3, "COMPLETED", owner, worker));
		RecordId noShow = publish(noShow(workId, owner, worker));

		consumer.poll();

		assertThat(trustScores.find(worker)).contains(new CurrentTrustScore(new BigDecimal("40.00"), 0, 1, 1));
		assertThat(trustScores.find(owner)).isEmpty();
		assertThat(jdbc.queryForMap("select event_id, worker_member_id, work_date, no_show_at from member_no_show_works"
			+ " where work_id = ?", workId))
			.containsEntry("worker_member_id", worker)
			.containsEntry("work_date", java.sql.Date.valueOf(LocalDate.of(2026, 11, 3)))
			.containsEntry("no_show_at", NO_SHOW_AT.withNano(123_457_000));
		JsonNode payload = latestTrustEvent(worker);
		assertThat(payload.path("trustScore").decimalValue()).isEqualByComparingTo("40.00");
		assertThat(payload.path("reviewCount").asLong()).isZero();
		assertThat(payload.path("noShowCount").asLong()).isEqualTo(1);
		assertThat(payload.path("policyVersion").asText()).isEqualTo("review-rating-no-show-v1");
		assertThat(pendingIds()).doesNotContain(completed, noShow);
	}

	@Test
	void noShowSubtractsFromReviewScoreAndLaterReviewsKeepPenalty() {
		long owner = member("OWNER");
		long worker = member("WORKER");
		reviews.write(owner, new WriteReviewRequest(completedWork(owner, worker), 5, null));
		assertThat(trustScores.find(worker).orElseThrow().trustScore()).isEqualByComparingTo("62.50");

		publish(noShow(IDS.incrementAndGet(), owner, worker));
		consumer.poll();
		assertThat(trustScores.find(worker)).contains(new CurrentTrustScore(new BigDecimal("52.50"), 1, 1, 2));

		reviews.write(owner, new WriteReviewRequest(completedWork(owner, worker), 4, null));
		// (9 + 9) / 5 = 3.6 → 65.00 - 10
		assertThat(trustScores.find(worker)).contains(new CurrentTrustScore(new BigDecimal("55.00"), 2, 1, 3));
		assertThat(outboxRevisions(worker)).containsExactly(1L, 2L, 3L);
	}

	@Test
	void redeliveredNoShowIsPenalizedOnce() {
		long owner = IDS.incrementAndGet();
		long worker = IDS.incrementAndGet();
		long workId = IDS.incrementAndGet();
		Map<String, String> fields = noShow(workId, owner, worker);
		RecordId first = publish(fields);
		consumer.poll();
		// relay 재발행은 같은 eventId, Outbox 재기록 사고는 새 eventId일 수 있다. 둘 다 같은 근무로 본다.
		RecordId second = publish(fields);
		RecordId third = publish(noShow(workId, owner, worker));
		consumer.poll();

		assertThat(trustScores.find(worker)).contains(new CurrentTrustScore(new BigDecimal("40.00"), 0, 1, 1));
		assertThat(pendingIds()).doesNotContain(first, second, third);
	}

	@Test
	void eachDistinctNoShowSubtractsAgainDownToZero() {
		long owner = IDS.incrementAndGet();
		long worker = IDS.incrementAndGet();
		for (int index = 0; index < 6; index++) publish(noShow(IDS.incrementAndGet(), owner, worker));

		consumer.poll();

		assertThat(trustScores.find(worker)).contains(new CurrentTrustScore(new BigDecimal("0.00"), 0, 6, 6));
		assertThat(jdbc.queryForObject("select no_show_count from member_review_stats where member_id = ?",
			Integer.class, worker)).isEqualTo(6);
	}

	@Test
	void invalidOrConflictingNoShowStaysPendingWithoutBlockingLaterRecords() {
		long owner = IDS.incrementAndGet();
		long worker = IDS.incrementAndGet();
		long ownerMarked = IDS.incrementAndGet();
		Map<String, String> ownerActor = noShow(ownerMarked, owner, worker);
		ownerActor.put("payload", ownerActor.get("payload").replace("\"actorRole\":\"SYSTEM\"", "\"actorRole\":\"OWNER\""));
		RecordId invalidActor = publish(ownerActor);
		long wrongRevision = IDS.incrementAndGet();
		RecordId invalidRevision = publish(event(wrongRevision, "WorkNoShow", 3, "NO_SHOW", owner, worker));
		long conflictingWork = IDS.incrementAndGet();
		publish(noShow(conflictingWork, owner, worker));
		consumer.poll();
		RecordId conflicting = publish(noShow(conflictingWork, owner, IDS.incrementAndGet()));
		long laterWork = IDS.incrementAndGet();
		RecordId later = publish(noShow(laterWork, owner, worker));

		consumer.poll();

		assertThat(noShowRecorded(ownerMarked)).isFalse();
		assertThat(noShowRecorded(wrongRevision)).isFalse();
		assertThat(noShowRecorded(laterWork)).isTrue();
		assertThat(trustScores.find(worker).orElseThrow().noShowCount()).isEqualTo(2);
		assertThat(pendingIds()).contains(invalidActor, invalidRevision, conflicting).doesNotContain(later);
	}

	// 리뷰와 노쇼가 같은 집계 행 잠금으로 직렬화되어 교착 없이 revision이 빠짐없이 이어지고 최종 점수가 모든 입력을 반영한다.
	@Test
	void concurrentReviewsAndNoShowsForSameWorkerSerializeWithoutLostUpdates() throws Exception {
		long worker = member("WORKER");
		int pairs = 4;
		List<Callable<Object>> tasks = new ArrayList<>();
		for (int index = 0; index < pairs; index++) {
			long owner = member("OWNER");
			long reviewWork = completedWork(owner, worker);
			WorkNoShow noShow = new WorkNoShow(UUID.randomUUID().toString(), IDS.incrementAndGet(), 1L, owner, worker,
				LocalDate.of(2026, 11, 3), NO_SHOW_AT.withNano(0));
			tasks.add(() -> reviews.write(owner, new WriteReviewRequest(reviewWork, 5, null)));
			tasks.add(() -> penalties.receive(noShow));
		}
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
		try {
			List<Future<Object>> futures = new ArrayList<>();
			for (Callable<Object> task : tasks) futures.add(executor.submit(() -> {
				start.await();
				return task.call();
			}));
			start.countDown();
			for (Future<Object> future : futures) future.get(30, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		// (9 + 20) / 7 = 4.142... → 78.57 - 40
		assertThat(trustScores.find(worker)).contains(new CurrentTrustScore(new BigDecimal("38.57"), 4, 4, 8));
		assertThat(outboxRevisions(worker)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
	}

	private JsonNode latestTrustEvent(long memberId) throws Exception {
		List<String> payloads = jdbc.queryForList("select payload from member_outbox where aggregate_type = 'MEMBER'"
			+ " and aggregate_id = ? order by revision desc", String.class, memberId);
		return mapper.readTree(payloads.get(0));
	}

	private List<Long> outboxRevisions(long memberId) {
		return jdbc.queryForList("select revision from member_outbox where aggregate_type = 'MEMBER'"
			+ " and aggregate_id = ? order by id", Long.class, memberId);
	}

	private boolean noShowRecorded(long workId) {
		return jdbc.queryForObject("select count(*) from member_no_show_works where work_id = ?", Long.class, workId) > 0;
	}

	private long member(String role) {
		long id = IDS.incrementAndGet();
		jdbc.update("insert into members(id, created_at, updated_at, name, email, phone_number, role, status, joined_at)"
				+ " values (?, now(6), now(6), ?, ?, ?, ?, 'ACTIVE', now(6))", id, "노쇼" + id, "noshow" + id + "@test.com",
			"010" + String.format("%08d", id % 100_000_000), role);
		return id;
	}

	private long completedWork(long owner, long worker) {
		long workId = IDS.incrementAndGet();
		reviewableWorks.receive(new WorkCompletion("no-show-review-" + workId, workId, workId + 1, owner, worker,
			LocalDate.of(2026, 11, 3), LocalDateTime.now(SCHEDULE_ZONE).minusHours(1)));
		return workId;
	}

	private Map<String, String> noShow(long workId, long owner, long worker) {
		return event(workId, "WorkNoShow", 4, "NO_SHOW", owner, worker);
	}

	// work-service는 SYSTEM 처리에서 actorMemberId를 null로 직렬화한다.
	private Map<String, String> event(long workId, String type, long revision, String status, long owner, long worker) {
		String eventId = UUID.randomUUID().toString();
		String payload = """
			{"eventId":"%s","eventType":"%s","aggregateId":%d,"revision":%d,"version":1,"occurredAt":"%s",
			 "workId":%d,"matchingId":%d,"jobPostId":%d,"ownerMemberId":%d,"workerMemberId":%d,
			 "actorMemberId":null,"actorRole":"SYSTEM","status":"%s","workDate":"2026-11-03",
			 "startTime":"09:00:00","endTime":"18:00:00","endTimeNextDay":false}
			""".formatted(eventId, type, workId, revision, NO_SHOW_AT, workId, workId + 2, workId + 1, owner, worker,
			status);
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("eventId", eventId);
		fields.put("aggregateType", "WORK");
		fields.put("aggregateId", Long.toString(workId));
		fields.put("eventType", type);
		fields.put("revision", Long.toString(revision));
		fields.put("version", "1");
		// Outbox DATETIME(6) 저장으로 마이크로초 미만이 반올림된 envelope 시각을 재현한다.
		fields.put("occurredAt", NO_SHOW_AT.withNano(123_457_000).toString());
		fields.put("payload", payload);
		return fields;
	}

	private RecordId publish(Map<String, String> fields) {
		MapRecord<String, String, String> record = StreamRecords.newRecord().in(STREAM).ofMap(fields);
		return redis.opsForStream().add(record);
	}

	private List<RecordId> pendingIds() {
		return redis.opsForStream()
			.pending(STREAM, Consumer.from(WorkNoShowEventConsumer.GROUP, WorkNoShowEventConsumer.CONSUMER),
				Range.unbounded(), 1000)
			.stream().map(PendingMessage::getId).toList();
	}
}
