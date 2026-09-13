package com.workernotfound.matching.external.redis.score;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.application.service.ApplicationQueueRecoveryService;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.domain.score.model.RankedApplicationScore;
import com.workernotfound.matching.domain.score.policy.ApplicationTimeScorePolicy;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingScoreQueueProjectionTests extends IntegrationTestSupport {

	private static final Long JOB_POST_ID = 10L;
	private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 13, 10, 0);

	@Autowired
	private ApplicationCommandService applicationCommandService;

	@Autowired
	private ApplicationQueueRecoveryService recoveryService;

	@Autowired
	private MatchingScoreQueueRepository scoreQueueRepository;

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

	@BeforeEach
	void cleanUp() {
		scoreSnapshotRepository.deleteAll();
		scoreBatchRepository.deleteAll();
		outboxEventRepository.deleteAll();
		historyRepository.deleteAll();
		applicationRepository.deleteAll();
		flushRedis();
	}

	@AfterEach
	void tearDown() {
		cleanUp();
	}

	@Test
	void recalculatesAndProjectsScoresAfterApplicationCommit() {
		Application first = createApplication(20L, 30L, BASE_TIME);
		Long previousBatchId = scoreQueueRepository.findMetadata(JOB_POST_ID)
			.orElseThrow()
			.scoreBatchId();
		Application second = createApplication(21L, 31L, BASE_TIME.plusMinutes(1));

		MatchingScoreQueueMetadata metadata = scoreQueueRepository.findMetadata(JOB_POST_ID)
			.orElseThrow();

		assertThat(metadata.policyVersion()).isEqualTo(ApplicationTimeScorePolicy.VERSION);
		assertThat(scoreQueueRepository.findScores(JOB_POST_ID, metadata.scoreBatchId()))
			.containsExactlyInAnyOrder(
				new RankedApplicationScore(first.getId(), new BigDecimal("100.0")),
				new RankedApplicationScore(second.getId(), new BigDecimal("50.0"))
			);
		assertThat(scoreQueueRepository.findScores(JOB_POST_ID, previousBatchId)).isEmpty();
		assertThat(scoreBatchRepository.count()).isEqualTo(2L);
	}

	@Test
	void recalculatesRemainingApplicationsAfterCancellation() {
		Application first = createApplication(20L, 30L, BASE_TIME);
		Application canceled = createApplication(21L, 31L, BASE_TIME.plusMinutes(1));

		applicationCommandService.cancel(canceled.getId(), 21L, "cancel-correlation-id");

		MatchingScoreQueueMetadata metadata = scoreQueueRepository.findMetadata(JOB_POST_ID)
			.orElseThrow();
		assertThat(scoreQueueRepository.findScores(JOB_POST_ID, metadata.scoreBatchId()))
			.containsExactly(new RankedApplicationScore(first.getId(), new BigDecimal("100.0")));
	}

	@Test
	void clearsRankedQueueWhenLastApplicationIsCanceled() {
		Application application = createApplication(20L, 30L, BASE_TIME);

		applicationCommandService.cancel(application.getId(), 20L, "cancel-correlation-id");

		assertThat(scoreQueueRepository.findMetadata(JOB_POST_ID)).isEmpty();
	}

	@Test
	void recoversLatestScoresFromMySqlWithoutCreatingAnotherBatch() {
		Application application = createApplication(20L, 30L, BASE_TIME);
		long batchCount = scoreBatchRepository.count();
		flushRedis();

		recoveryService.recover();

		MatchingScoreQueueMetadata metadata = scoreQueueRepository.findMetadata(JOB_POST_ID)
			.orElseThrow();
		assertThat(scoreQueueRepository.findScores(JOB_POST_ID, metadata.scoreBatchId()))
			.containsExactly(new RankedApplicationScore(application.getId(), new BigDecimal("100.0")));
		assertThat(scoreBatchRepository.count()).isEqualTo(batchCount);
	}

	@Test
	void recalculatesWhenLatestBatchDoesNotContainAllActiveApplications() {
		Application scored = createApplication(20L, 30L, BASE_TIME);
		Application unscored = applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(JOB_POST_ID)
			.workerMemberId(21L)
			.jobApplicationAdmissionId(31L)
			.appliedAt(BASE_TIME.plusMinutes(1))
			.build());
		long batchCount = scoreBatchRepository.count();
		flushRedis();

		recoveryService.recover();

		MatchingScoreQueueMetadata metadata = scoreQueueRepository.findMetadata(JOB_POST_ID)
			.orElseThrow();
		Set<Long> applicationIds = scoreQueueRepository.findScores(JOB_POST_ID, metadata.scoreBatchId())
			.stream()
			.map(RankedApplicationScore::applicationId)
			.collect(java.util.stream.Collectors.toSet());
		assertThat(applicationIds).containsExactlyInAnyOrder(scored.getId(), unscored.getId());
		assertThat(scoreBatchRepository.count()).isEqualTo(batchCount + 1);
	}

	private Application createApplication(
		Long workerMemberId,
		Long admissionId,
		LocalDateTime appliedAt
	) {
		return applicationCommandService.create(
			JOB_POST_ID,
			workerMemberId,
			admissionId,
			appliedAt,
			"create-correlation-id-" + admissionId
		);
	}

	private void flushRedis() {
		try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
			connection.serverCommands().flushDb();
		}
	}
}
