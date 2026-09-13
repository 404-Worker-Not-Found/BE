package com.workernotfound.matching.domain.application.repository;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.model.OwnerApplicantRow;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.model.MatchingScoreResult;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class OwnerApplicantRepositoryTests extends IntegrationTestSupport {

	private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 14, 9, 0);

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private MatchingScoreBatchRepository batchRepository;

	@Autowired
	private MatchingScoreSnapshotRepository snapshotRepository;

	@Test
	void sortsReadyScoresFirstAndKeepsUnscoredApplicants() {
		Application lowerScore = saveApplication(20L, 100L, 30L, BASE_TIME);
		Application higherScore = saveApplication(21L, 100L, 31L, BASE_TIME.plusMinutes(1));
		Application failed = saveApplication(22L, 100L, 32L, BASE_TIME.plusMinutes(2));
		Application unscored = saveApplication(23L, 100L, 33L, BASE_TIME.plusMinutes(3));
		saveApplication(24L, 200L, 34L, BASE_TIME.plusMinutes(4));
		MatchingScoreBatch batch = saveBatch();
		completeSnapshot(batch, lowerScore, "50.0000");
		completeSnapshot(batch, higherScore, "90.0000");
		failSnapshot(batch, failed);
		batch.complete(BASE_TIME.plusMinutes(5));
		batchRepository.flush();

		Page<OwnerApplicantRow> result = applicationRepository.findOwnerApplicantRows(
			10L,
			100L,
			batch.getId(),
			ApplicationStatus.APPLIED,
			PageRequest.of(0, 10)
		);

		assertThat(result.getContent())
			.extracting(row -> row.application().getId())
			.containsExactly(higherScore.getId(), lowerScore.getId(), failed.getId(), unscored.getId());
		assertThat(result.getTotalElements()).isEqualTo(4);
		assertThat(result.getContent().get(2).scoreSnapshot()).isNotNull();
		assertThat(result.getContent().get(3).scoreSnapshot()).isNull();
	}

	private Application saveApplication(
		Long workerMemberId,
		Long ownerMemberId,
		Long admissionId,
		LocalDateTime appliedAt
	) {
		return applicationRepository.saveAndFlush(Application.builder()
			.jobPostId(10L)
			.workerMemberId(workerMemberId)
			.ownerMemberId(ownerMemberId)
			.jobApplicationAdmissionId(admissionId)
			.appliedAt(appliedAt)
			.build());
	}

	private MatchingScoreBatch saveBatch() {
		return batchRepository.saveAndFlush(MatchingScoreBatch.builder()
			.jobPostId(10L)
			.policyVersion("policy-v1")
			.startedAt(BASE_TIME)
			.build());
	}

	private void completeSnapshot(
		MatchingScoreBatch batch,
		Application application,
		String score
	) {
		MatchingScoreSnapshot snapshot = snapshotRepository.saveAndFlush(pendingSnapshot(batch, application));
		BigDecimal value = new BigDecimal(score);
		snapshot.complete(new MatchingScoreResult(
			value,
			value,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		), BASE_TIME.plusMinutes(5));
		snapshotRepository.flush();
	}

	private void failSnapshot(MatchingScoreBatch batch, Application application) {
		MatchingScoreSnapshot snapshot = snapshotRepository.saveAndFlush(pendingSnapshot(batch, application));
		snapshot.fail(BASE_TIME.plusMinutes(5));
		snapshotRepository.flush();
	}

	private MatchingScoreSnapshot pendingSnapshot(
		MatchingScoreBatch batch,
		Application application
	) {
		return MatchingScoreSnapshot.builder()
			.scoreBatch(batch)
			.application(application)
			.inputSnapshot("{}")
			.missingInputs("[\"RATING\"]")
			.calculatedAt(BASE_TIME)
			.build();
	}
}
