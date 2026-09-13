package com.workernotfound.matching.domain.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.entity.enums.ScoreBatchStatus;
import com.workernotfound.matching.domain.score.model.MatchingScoreResult;
import com.workernotfound.matching.domain.score.policy.ApplicationTimeScorePolicy;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import com.workernotfound.matching.domain.score.service.MatchingScoreCalculationService;
import com.workernotfound.matching.domain.score.service.MatchingScoreFindService;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class MatchingScoreCalculationServiceTests extends IntegrationTestSupport {

	private static final Long JOB_POST_ID = 10L;
	private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 13, 10, 0);

	@Autowired
	private MatchingScoreCalculationService calculationService;

	@Autowired
	private MatchingScoreFindService findService;

	@Autowired
	private ApplicationTimeScorePolicy scorePolicy;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private MatchingScoreBatchRepository batchRepository;

	@Autowired
	private MatchingScoreSnapshotRepository snapshotRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void calculatesApplicationTimeScoresAndKeepsUnavailableInputsMissing() throws Exception {
		Application latest = saveApplication(20L, 30L, BASE_TIME.plusMinutes(2));
		Application earliest = saveApplication(21L, 31L, BASE_TIME);
		Application middle = saveApplication(22L, 32L, BASE_TIME.plusMinutes(1));

		MatchingScoreBatch batch = calculationService.calculate(JOB_POST_ID).orElseThrow();
		List<MatchingScoreSnapshot> ranked = findService.findRankedSnapshots(batch.getId());

		assertThat(batch.getStatus()).isEqualTo(ScoreBatchStatus.READY);
		assertThat(batch.getPolicyVersion()).isEqualTo(ApplicationTimeScorePolicy.VERSION);
		assertThat(ranked)
			.extracting(snapshot -> snapshot.getApplication().getId())
			.containsExactly(earliest.getId(), middle.getId(), latest.getId());
		assertThat(ranked)
			.extracting(MatchingScoreSnapshot::getTotalScore)
			.containsExactly(
				new BigDecimal("100.0000"),
				new BigDecimal("66.6667"),
				new BigDecimal("33.3333")
			);
		assertMissingInputs(ranked.get(0));
	}

	@Test
	void createsNewBatchWithoutOverwritingPreviousScores() {
		saveApplication(20L, 30L, BASE_TIME);
		MatchingScoreBatch first = calculationService.calculate(JOB_POST_ID).orElseThrow();
		saveApplication(21L, 31L, BASE_TIME.plusMinutes(1));

		MatchingScoreBatch second = calculationService.calculate(JOB_POST_ID).orElseThrow();

		assertThat(second.getId()).isNotEqualTo(first.getId());
		assertThat(batchRepository.count()).isEqualTo(2L);
		assertThat(snapshotRepository.count()).isEqualTo(3L);
		assertThat(findService.findLatestReadyBatch(JOB_POST_ID)).contains(second);
	}

	@Test
	void skipsBatchWhenThereAreNoAppliedApplications() {
		Application application = saveApplication(20L, 30L, BASE_TIME);
		application.cancel();
		applicationRepository.flush();

		Optional<MatchingScoreBatch> result = calculationService.calculate(JOB_POST_ID);

		assertThat(result).isEmpty();
		assertThat(batchRepository.count()).isZero();
	}

	@Test
	void ordersEqualScoresByAppliedAtAndApplicationId() {
		Application later = saveApplication(20L, 30L, BASE_TIME.plusMinutes(1));
		Application first = saveApplication(21L, 31L, BASE_TIME);
		Application second = saveApplication(22L, 32L, BASE_TIME);
		MatchingScoreBatch batch = saveReadyBatch();
		saveReadySnapshot(batch, later);
		saveReadySnapshot(batch, first);
		saveReadySnapshot(batch, second);

		List<MatchingScoreSnapshot> ranked = findService.findRankedSnapshots(batch.getId());

		assertThat(ranked)
			.extracting(snapshot -> snapshot.getApplication().getId())
			.containsExactly(first.getId(), second.getId(), later.getId());
	}

	@Test
	void rejectsInvalidApplicationPosition() {
		assertThatThrownBy(() -> scorePolicy.calculate(1, 1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private void assertMissingInputs(MatchingScoreSnapshot snapshot) throws Exception {
		assertThat(snapshot.getRatingScore()).isNull();
		assertThat(snapshot.getExperienceScore()).isNull();
		assertThat(snapshot.getActivityScore()).isNull();
		assertThat(snapshot.getArrivalScore()).isNull();
		assertThat(snapshot.getNoShowScore()).isNull();
		JsonNode input = objectMapper.readTree(snapshot.getInputSnapshot());
		assertThat(input.path("rating").isNull()).isTrue();
		assertThat(input.path("industryExperienceMonths").isNull()).isTrue();
		assertThat(input.path("online").isNull()).isTrue();
		assertThat(input.path("expectedArrivalMinutes").isNull()).isTrue();
		assertThat(input.path("noShowProbability").isNull()).isTrue();
		assertThat(objectMapper.readTree(snapshot.getMissingInputs()).toString())
			.isEqualTo("[\"RATING\",\"INDUSTRY_EXPERIENCE\",\"ONLINE_STATUS\"," +
				"\"EXPECTED_ARRIVAL_TIME\",\"NO_SHOW_RISK\"]");
	}

	private Application saveApplication(
		Long workerMemberId,
		Long admissionId,
		LocalDateTime appliedAt
	) {
		return applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(JOB_POST_ID)
			.workerMemberId(workerMemberId)
			.jobApplicationAdmissionId(admissionId)
			.appliedAt(appliedAt)
			.build());
	}

	private MatchingScoreBatch saveReadyBatch() {
		MatchingScoreBatch batch = batchRepository.saveAndFlush(MatchingScoreBatch.builder()
			.jobPostId(JOB_POST_ID)
			.policyVersion(ApplicationTimeScorePolicy.VERSION)
			.startedAt(BASE_TIME)
			.build());
		batch.complete(BASE_TIME.plusSeconds(1));
		batchRepository.flush();
		return batch;
	}

	private void saveReadySnapshot(MatchingScoreBatch batch, Application application) {
		MatchingScoreSnapshot snapshot = MatchingScoreSnapshot.builder()
			.scoreBatch(batch)
			.application(application)
			.inputSnapshot("{}")
			.missingInputs("[]")
			.calculatedAt(BASE_TIME)
			.build();
		snapshot.complete(equalScore(), BASE_TIME.plusSeconds(1));
		snapshotRepository.saveAndFlush(snapshot);
	}

	private MatchingScoreResult equalScore() {
		return new MatchingScoreResult(
			new BigDecimal("50.0000"),
			new BigDecimal("50.0000"),
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}
}
