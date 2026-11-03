package com.workernotfound.member.domain.review.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.member.domain.outbox.MemberOutboxRelay;
import com.workernotfound.member.domain.review.dto.request.WriteReviewRequest;
import com.workernotfound.member.domain.review.repository.MemberTrustScoreRepository;
import com.workernotfound.member.domain.review.repository.MemberTrustScoreRepository.CurrentTrustScore;
import com.workernotfound.member.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 리뷰를 받을 때마다 같은 트랜잭션에서 신뢰 점수와 TrustScoreUpdated Outbox를 남기고, relay가 공통 envelope로 발행한다.
class TrustScoreRecordingTests extends IntegrationTestSupport {
	private static final AtomicLong IDS = new AtomicLong(8_600_000);
	private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Seoul");
	private static final String STREAM = "member:domain-events";

	@Autowired
	private ReviewCommandService commands;

	@Autowired
	private ReviewableWorkReceiptService receipts;

	@Autowired
	private MemberTrustScoreRepository trustScores;

	@Autowired
	private MemberOutboxRelay relay;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void eachReceivedReviewUpdatesTrustScoreAndAppendsOrderedEvent() throws Exception {
		long owner = member("OWNER");
		long worker = member("WORKER");
		long first = completedWork(owner, worker);
		long second = completedWork(owner, worker);

		commands.write(owner, new WriteReviewRequest(first, 5, null));
		assertThat(trustScores.find(worker)).contains(new CurrentTrustScore(new java.math.BigDecimal("62.50"), 1, 1));
		commands.write(owner, new WriteReviewRequest(second, 4, null));

		CurrentTrustScore current = trustScores.find(worker).orElseThrow();
		assertThat(current.trustScore()).isEqualByComparingTo("65.00");
		assertThat(current.reviewCount()).isEqualTo(2);
		assertThat(current.revision()).isEqualTo(2);
		assertThat(trustScores.find(owner)).isEmpty();
		List<Map<String, Object>> events = outboxOf(worker);
		assertThat(events).extracting(row -> ((Number) row.get("revision")).longValue()).containsExactly(1L, 2L);
		JsonNode latest = mapper.readTree((String) events.get(1).get("payload"));
		assertThat(latest.path("eventType").asText()).isEqualTo("TrustScoreUpdated");
		assertThat(latest.path("memberId").asLong()).isEqualTo(worker);
		assertThat(latest.path("revision").asLong()).isEqualTo(2);
		assertThat(latest.path("version").asInt()).isEqualTo(1);
		assertThat(latest.path("trustScore").decimalValue()).isEqualByComparingTo("65.00");
		assertThat(latest.path("reviewCount").asLong()).isEqualTo(2);
		assertThat(latest.path("policyVersion").asText()).isEqualTo("review-rating-v1");
	}

	@Test
	void repeatedSameReviewDoesNotChangeTrustScoreAgain() {
		long owner = member("OWNER");
		long worker = member("WORKER");
		long work = completedWork(owner, worker);

		commands.write(owner, new WriteReviewRequest(work, 3, "보통"));
		commands.write(owner, new WriteReviewRequest(work, 3, "보통"));

		assertThat(trustScores.find(worker).orElseThrow().revision()).isEqualTo(1);
		assertThat(outboxOf(worker)).hasSize(1);
	}

	@Test
	void concurrentReviewsForSameMemberProduceDistinctRevisionsAndFinalTotals() throws Exception {
		long worker = member("WORKER");
		int reviewers = 6;
		List<long[]> targets = new ArrayList<>();
		for (int index = 0; index < reviewers; index++) {
			long owner = member("OWNER");
			targets.add(new long[]{owner, completedWork(owner, worker)});
		}
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(reviewers);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (long[] target : targets) {
				Callable<Object> task = () -> {
					start.await();
					return commands.write(target[0], new WriteReviewRequest(target[1], 5, null));
				};
				futures.add(executor.submit(task));
			}
			start.countDown();
			for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		CurrentTrustScore current = trustScores.find(worker).orElseThrow();
		assertThat(current.reviewCount()).isEqualTo(reviewers);
		assertThat(current.revision()).isEqualTo(reviewers);
		assertThat(current.trustScore()).isEqualByComparingTo("83.33"); // (9 + 30) / 9 = 4.333...
		assertThat(outboxOf(worker)).extracting(row -> ((Number) row.get("revision")).longValue())
			.containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L);
		Map<String, Object> last = outboxOf(worker).stream()
			.filter(row -> ((Number) row.get("revision")).longValue() == reviewers).findFirst().orElseThrow();
		assertThat(mapper.readTree((String) last.get("payload")).path("reviewCount").asLong()).isEqualTo(reviewers);
	}

	@Test
	void relayPublishesTrustScoreEventWithCommonEnvelope() throws Exception {
		long owner = member("OWNER");
		long worker = member("WORKER");
		commands.write(owner, new WriteReviewRequest(completedWork(owner, worker), 4, null));
		Map<String, Object> outbox = outboxOf(worker).get(0);

		relay.relay();

		assertThat(jdbc.queryForObject("select published_at is not null from member_outbox where id = ?", Boolean.class,
			outbox.get("id"))).isTrue();
		MapRecord<String, Object, Object> record = redis.opsForStream().read(StreamOffset.fromStart(STREAM)).stream()
			.filter(candidate -> outbox.get("event_id").equals(candidate.getValue().get("eventId")))
			.findFirst().orElseThrow();
		Map<Object, Object> fields = record.getValue();
		assertThat(fields).containsEntry("aggregateType", "MEMBER")
			.containsEntry("aggregateId", Long.toString(worker))
			.containsEntry("eventType", "TrustScoreUpdated")
			.containsEntry("revision", "1")
			.containsEntry("version", "1");
		assertThat(LocalDateTime.parse((String) fields.get("occurredAt"))).isNotNull();
		assertThat(mapper.readTree((String) fields.get("payload")).path("trustScore").decimalValue())
			.isEqualByComparingTo("56.25"); // (9 + 4) / 4 = 3.25
	}

	private List<Map<String, Object>> outboxOf(long memberId) {
		return jdbc.queryForList("select id, event_id, revision, payload from member_outbox"
			+ " where aggregate_type = 'MEMBER' and aggregate_id = ? order by id", memberId);
	}

	private long member(String role) {
		long id = IDS.incrementAndGet();
		jdbc.update("insert into members(id, created_at, updated_at, name, email, phone_number, role, status, joined_at)"
				+ " values (?, now(6), now(6), ?, ?, ?, ?, 'ACTIVE', now(6))", id, "신뢰" + id, "trust" + id + "@test.com",
			"010" + String.format("%08d", id % 100_000_000), role);
		return id;
	}

	private long completedWork(long owner, long worker) {
		long workId = IDS.incrementAndGet();
		receipts.receive(new WorkCompletion("trust-event-" + workId, workId, workId + 1, owner, worker,
			LocalDate.of(2026, 10, 27), LocalDateTime.now(SCHEDULE_ZONE).minusHours(1)));
		return workId;
	}
}
