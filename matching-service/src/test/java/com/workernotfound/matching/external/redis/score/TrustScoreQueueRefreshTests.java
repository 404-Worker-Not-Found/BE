package com.workernotfound.matching.external.redis.score;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.application.service.ApplicationQueueRecoveryService;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.model.RankedApplicationScore;
import com.workernotfound.matching.domain.score.model.TrustScoreUpdate;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import com.workernotfound.matching.domain.score.service.MatchingScoreFindService;
import com.workernotfound.matching.domain.score.service.TrustScoreProjectionService;
import com.workernotfound.matching.external.redis.TrustScoreEventConsumer;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 회원 신뢰 점수 사본이 바뀌면 그 회원이 지원 중인 공고의 점수 묶음을 다시 계산하고, 중복·역순 이벤트는 무시한다.
class TrustScoreQueueRefreshTests extends IntegrationTestSupport {

	private static final Long JOB_POST_ID = 11L;
	private static final Long FIRST_WORKER = 9_100_001L;
	private static final Long SECOND_WORKER = 9_100_002L;
	private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 13, 10, 0);
	private static final String STREAM = "member:domain-events";

	@Autowired
	private ApplicationCommandService applicationCommandService;

	@Autowired
	private ApplicationQueueRecoveryService recoveryService;

	@Autowired
	private TrustScoreProjectionService projectionService;

	@Autowired
	private TrustScoreEventConsumer consumer;

	@Autowired
	private MatchingScoreQueueRepository scoreQueueRepository;

	@Autowired
	private MatchingScoreFindService scoreFindService;

	@Autowired
	private MatchingScoreSnapshotRepository scoreSnapshotRepository;

	@Autowired
	private MatchingScoreBatchRepository scoreBatchRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private ApplicationStatusHistoryRepository historyRepository;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	@AfterEach
	void cleanUp() {
		scoreSnapshotRepository.deleteAll();
		scoreBatchRepository.deleteAll();
		outboxEventRepository.deleteAll();
		historyRepository.deleteAll();
		applicationRepository.deleteAll();
		jdbc.update("delete from member_trust_scores");
		try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
			connection.serverCommands().flushDb();
		}
	}

	@Test
	void trustScoreChangeRecalculatesQueueOfJobsTheWorkerAppliedTo() throws Exception {
		Application first = createApplication(FIRST_WORKER, 9_100_101L, BASE_TIME);
		Application second = createApplication(SECOND_WORKER, 9_100_102L, BASE_TIME.plusMinutes(1));
		long previousBatchId = currentBatchId();

		assertThat(projectionService.receive(update(SECOND_WORKER, 1, "90.00"))).isTrue();

		long batchId = currentBatchId();
		assertThat(batchId).isNotEqualTo(previousBatchId);
		// 먼저 지원한 신뢰 점수 없는 지원자: 지원 시각 100. 늦게 지원한 지원자: 50 × 0.5 + 90 × 0.5 = 70.
		assertThat(scoreQueueRepository.findScores(JOB_POST_ID, batchId)).containsExactlyInAnyOrder(
			new RankedApplicationScore(first.getId(), new BigDecimal("100.0")),
			new RankedApplicationScore(second.getId(), new BigDecimal("70.0"))
		);
		MatchingScoreSnapshot scored = scoreFindService.findSnapshot(batchId, second.getId()).orElseThrow();
		assertThat(scored.getRatingScore()).isEqualByComparingTo("90.0000");
		assertThat(scored.getAppliedTimeScore()).isEqualByComparingTo("50.0000");
		assertThat(mapper.readTree(scored.getInputSnapshot()).path("rating").decimalValue()).isEqualByComparingTo("90.00");
		assertThat(mapper.readTree(scored.getMissingInputs()).toString()).doesNotContain("RATING");
		MatchingScoreSnapshot unscored = scoreFindService.findSnapshot(batchId, first.getId()).orElseThrow();
		assertThat(unscored.getRatingScore()).isNull();
		assertThat(mapper.readTree(unscored.getMissingInputs()).toString()).contains("RATING");
	}

	@Test
	void duplicateAndOlderRevisionsDoNotChangeScoresOrCreateBatches() {
		createApplication(FIRST_WORKER, 9_100_101L, BASE_TIME);
		projectionService.receive(update(FIRST_WORKER, 2, "80.00"));
		long batchCount = scoreBatchRepository.count();

		assertThat(projectionService.receive(update(FIRST_WORKER, 2, "10.00"))).isFalse();
		assertThat(projectionService.receive(update(FIRST_WORKER, 1, "20.00"))).isFalse();

		assertThat(jdbc.queryForObject("select trust_score from member_trust_scores where member_id = ?",
			BigDecimal.class, FIRST_WORKER)).isEqualByComparingTo("80.00");
		assertThat(scoreBatchRepository.count()).isEqualTo(batchCount);
	}

	@Test
	void recoveryKeepsBatchWhileTrustScoresAreUnchangedAndRecalculatesAfterMissedRefresh() {
		Application application = createApplication(FIRST_WORKER, 9_100_101L, BASE_TIME);
		projectionService.receive(update(FIRST_WORKER, 1, "60.00"));
		long batchCount = scoreBatchRepository.count();

		recoveryService.recover();
		assertThat(scoreBatchRepository.count()).isEqualTo(batchCount);

		// 커밋 뒤 대기열 갱신을 놓친 경우를 흉내 내 사본만 바꾼다. 주기 복구가 바뀐 신뢰 점수를 보고 다시 계산한다.
		jdbc.update("update member_trust_scores set trust_score = 40.00, revision = 2 where member_id = ?", FIRST_WORKER);
		recoveryService.recover();

		assertThat(scoreBatchRepository.count()).isEqualTo(batchCount + 1);
		assertThat(scoreQueueRepository.findScores(JOB_POST_ID, currentBatchId()))
			.containsExactly(new RankedApplicationScore(application.getId(), new BigDecimal("70.0")));
	}

	@Test
	void consumerAppliesTrustScoreEventsAndKeepsInvalidRecordsPending() throws Exception {
		createApplication(FIRST_WORKER, 9_100_101L, BASE_TIME);
		addRecord(envelope(update(FIRST_WORKER, 1, "75.00")));
		Map<String, String> mismatched = envelope(update(SECOND_WORKER, 1, "75.00"));
		mismatched.put("revision", "2");
		addRecord(mismatched);
		Map<String, String> otherEvent = envelope(update(SECOND_WORKER, 1, "75.00"));
		otherEvent.put("eventType", "MemberWithdrawn");
		addRecord(otherEvent);

		consumer.poll();

		assertThat(jdbc.queryForObject("select trust_score from member_trust_scores where member_id = ?",
			BigDecimal.class, FIRST_WORKER)).isEqualByComparingTo("75.00");
		assertThat(jdbc.queryForObject("select count(*) from member_trust_scores where member_id = ?",
			Long.class, SECOND_WORKER)).isZero();
		assertThat(redisTemplate.opsForStream().pending(STREAM, "matching-trust-score-v1").getTotalPendingMessages())
			.isEqualTo(1);
		assertThat(scoreQueueRepository.findScores(JOB_POST_ID, currentBatchId()))
			.extracting(RankedApplicationScore::totalScore)
			.containsExactly(new BigDecimal("87.5"));
	}

	private long currentBatchId() {
		return scoreQueueRepository.findMetadata(JOB_POST_ID).orElseThrow().scoreBatchId();
	}

	private TrustScoreUpdate update(Long memberId, long revision, String trustScore) {
		return new TrustScoreUpdate(UUID.randomUUID().toString(), memberId, revision, new BigDecimal(trustScore), 1,
			"review-rating-v1");
	}

	private Map<String, String> envelope(TrustScoreUpdate update) throws Exception {
		Map<String, Object> payload = new HashMap<>();
		payload.put("eventId", update.eventId());
		payload.put("eventType", "TrustScoreUpdated");
		payload.put("memberId", update.memberId());
		payload.put("revision", update.revision());
		payload.put("version", 1);
		payload.put("trustScore", update.trustScore());
		payload.put("reviewCount", update.reviewCount());
		payload.put("policyVersion", update.policyVersion());
		Map<String, String> fields = new HashMap<>();
		fields.put("eventId", update.eventId());
		fields.put("aggregateType", "MEMBER");
		fields.put("aggregateId", Long.toString(update.memberId()));
		fields.put("eventType", "TrustScoreUpdated");
		fields.put("revision", Long.toString(update.revision()));
		fields.put("version", "1");
		fields.put("occurredAt", BASE_TIME.toString());
		fields.put("payload", mapper.writeValueAsString(payload));
		return fields;
	}

	private void addRecord(Map<String, String> fields) {
		redisTemplate.opsForStream().add(StreamRecords.newRecord().in(STREAM).ofMap(fields));
	}

	private Application createApplication(Long workerMemberId, Long admissionId, LocalDateTime appliedAt) {
		return applicationCommandService.create(
			JOB_POST_ID,
			workerMemberId,
			100L,
			admissionId,
			1L,
			appliedAt,
			LocalDateTime.now().plusDays(1),
			"trust-correlation-id-" + admissionId
		);
	}
}
