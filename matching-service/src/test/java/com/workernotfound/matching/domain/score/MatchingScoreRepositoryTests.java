package com.workernotfound.matching.domain.score;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.entity.enums.ScoreBatchStatus;
import com.workernotfound.matching.domain.score.entity.enums.ScoreCalculationStatus;
import com.workernotfound.matching.domain.score.model.MatchingScoreResult;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class MatchingScoreRepositoryTests extends IntegrationTestSupport {

	private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 9, 11, 10, 0);

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private MatchingScoreBatchRepository batchRepository;

	@Autowired
	private MatchingScoreSnapshotRepository snapshotRepository;

	@Test
	void savesPendingSnapshotWithMissingInputs() {
		Application application = saveApplication(10L, 20L, 30L);
		MatchingScoreBatch batch = saveBatch(10L, "policy-v1");

		MatchingScoreSnapshot snapshot = snapshotRepository.saveAndFlush(
			pendingSnapshot(batch, application)
		);

		assertThat(snapshot.getCalculationStatus()).isEqualTo(ScoreCalculationStatus.PENDING);
		assertThat(snapshot.getTotalScore()).isNull();
		assertThat(snapshot.getRatingScore()).isNull();
		assertThat(snapshot.getMissingInputs()).isEqualTo("[\"rating\",\"experience\"]");
	}

	@Test
	void completesSnapshotWithoutFabricatingMissingComponentScores() {
		Application application = saveApplication(10L, 20L, 30L);
		MatchingScoreBatch batch = saveBatch(10L, "policy-v1");
		MatchingScoreSnapshot snapshot = snapshotRepository.saveAndFlush(
			pendingSnapshot(batch, application)
		);

		snapshot.complete(scoreResult(), STARTED_AT.plusSeconds(1));
		snapshotRepository.flush();

		assertThat(snapshot.getCalculationStatus()).isEqualTo(ScoreCalculationStatus.READY);
		assertThat(snapshot.getTotalScore()).isEqualByComparingTo("80.0000");
		assertThat(snapshot.getAppliedTimeScore()).isEqualByComparingTo("80.0000");
		assertThat(snapshot.getRatingScore()).isNull();
	}

	@Test
	void rejectsReadyResultWithoutTotalScore() {
		assertThatThrownBy(() -> new MatchingScoreResult(
			null,
			new BigDecimal("80.0000"),
			null,
			null,
			null,
			null,
			null,
			null,
			null
		)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void failsPendingSnapshotWithoutTotalScore() {
		Application application = saveApplication(10L, 20L, 30L);
		MatchingScoreBatch batch = saveBatch(10L, "policy-v1");
		MatchingScoreSnapshot snapshot = snapshotRepository.saveAndFlush(
			pendingSnapshot(batch, application)
		);

		snapshot.fail(STARTED_AT.plusSeconds(1));
		snapshotRepository.flush();

		assertThat(snapshot.getCalculationStatus()).isEqualTo(ScoreCalculationStatus.FAILED);
		assertThat(snapshot.getTotalScore()).isNull();
	}

	@Test
	void rejectsDuplicateApplicationInSameBatch() {
		Application application = saveApplication(10L, 20L, 30L);
		MatchingScoreBatch batch = saveBatch(10L, "policy-v1");
		snapshotRepository.saveAndFlush(pendingSnapshot(batch, application));

		assertThatThrownBy(() -> snapshotRepository.saveAndFlush(
			pendingSnapshot(batch, application)
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsSameApplicationInDifferentBatches() {
		Application application = saveApplication(10L, 20L, 30L);
		MatchingScoreBatch firstBatch = saveBatch(10L, "policy-v1");
		MatchingScoreBatch secondBatch = saveBatch(10L, "policy-v2");

		snapshotRepository.saveAndFlush(pendingSnapshot(firstBatch, application));
		snapshotRepository.saveAndFlush(pendingSnapshot(secondBatch, application));

		assertThat(snapshotRepository.count()).isEqualTo(2L);
	}

	@Test
	void completesCalculatingBatchOnce() {
		MatchingScoreBatch batch = saveBatch(10L, "policy-v1");

		batch.complete(STARTED_AT.plusMinutes(1));
		batchRepository.flush();

		assertThat(batch.getStatus()).isEqualTo(ScoreBatchStatus.READY);
		assertThat(batch.getCompletedAt()).isEqualTo(STARTED_AT.plusMinutes(1));
		assertThatThrownBy(() -> batch.fail(STARTED_AT.plusMinutes(2)))
			.isInstanceOf(IllegalStateException.class);
	}

	private Application saveApplication(Long jobPostId, Long workerMemberId, Long admissionId) {
		return applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(jobPostId)
			.workerMemberId(workerMemberId)
			.jobApplicationAdmissionId(admissionId)
			.appliedAt(STARTED_AT)
			.build());
	}

	private MatchingScoreBatch saveBatch(Long jobPostId, String policyVersion) {
		return batchRepository.saveAndFlush(MatchingScoreBatch.builder()
			.jobPostId(jobPostId)
			.policyVersion(policyVersion)
			.startedAt(STARTED_AT)
			.build());
	}

	private MatchingScoreSnapshot pendingSnapshot(
		MatchingScoreBatch batch,
		Application application
	) {
		return MatchingScoreSnapshot.builder()
			.scoreBatch(batch)
			.application(application)
			.inputSnapshot("{\"appliedAt\":\"2026-09-11T10:00:00\"}")
			.missingInputs("[\"rating\",\"experience\"]")
			.calculatedAt(STARTED_AT)
			.build();
	}

	private MatchingScoreResult scoreResult() {
		return new MatchingScoreResult(
			new BigDecimal("80.0000"),
			new BigDecimal("80.0000"),
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
