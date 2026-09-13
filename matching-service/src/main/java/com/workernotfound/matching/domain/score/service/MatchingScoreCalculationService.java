package com.workernotfound.matching.domain.score.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.model.MatchingScoreInput;
import com.workernotfound.matching.domain.score.model.ScoreInputType;
import com.workernotfound.matching.domain.score.policy.ApplicationTimeScorePolicy;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingScoreCalculationService {

	private static final List<ScoreInputType> MISSING_INPUTS = List.of(
		ScoreInputType.RATING,
		ScoreInputType.INDUSTRY_EXPERIENCE,
		ScoreInputType.ONLINE_STATUS,
		ScoreInputType.EXPECTED_ARRIVAL_TIME,
		ScoreInputType.NO_SHOW_RISK
	);

	private final ApplicationRepository applicationRepository;
	private final MatchingScoreBatchRepository batchRepository;
	private final MatchingScoreSnapshotRepository snapshotRepository;
	private final ApplicationTimeScorePolicy scorePolicy;
	private final ObjectMapper objectMapper;

	@Transactional
	public Optional<MatchingScoreBatch> calculate(Long jobPostId) {
		List<Application> applications = applicationRepository
			.findByJobPostIdAndStatusOrderByAppliedAtAscIdAsc(jobPostId, ApplicationStatus.APPLIED);
		if (applications.isEmpty()) {
			return Optional.empty();
		}

		LocalDateTime startedAt = LocalDateTime.now();
		MatchingScoreBatch batch = saveBatch(jobPostId, startedAt);
		saveSnapshots(batch, applications, startedAt);
		batch.complete(LocalDateTime.now());
		batchRepository.flush();
		return Optional.of(batch);
	}

	private MatchingScoreBatch saveBatch(Long jobPostId, LocalDateTime startedAt) {
		return batchRepository.saveAndFlush(MatchingScoreBatch.builder()
			.jobPostId(jobPostId)
			.policyVersion(ApplicationTimeScorePolicy.VERSION)
			.startedAt(startedAt)
			.build());
	}

	private void saveSnapshots(
		MatchingScoreBatch batch,
		List<Application> applications,
		LocalDateTime calculatedAt
	) {
		for (int position = 0; position < applications.size(); position++) {
			MatchingScoreSnapshot snapshot = pendingSnapshot(
				batch,
				applications.get(position),
				calculatedAt
			);
			snapshot.complete(scorePolicy.calculate(position, applications.size()), calculatedAt);
			snapshotRepository.save(snapshot);
		}
		snapshotRepository.flush();
	}

	private MatchingScoreSnapshot pendingSnapshot(
		MatchingScoreBatch batch,
		Application application,
		LocalDateTime calculatedAt
	) {
		return MatchingScoreSnapshot.builder()
			.scoreBatch(batch)
			.application(application)
			.inputSnapshot(toJson(scoreInput(application)))
			.missingInputs(toJson(MISSING_INPUTS))
			.calculatedAt(calculatedAt)
			.build();
	}

	private MatchingScoreInput scoreInput(Application application) {
		return new MatchingScoreInput(
			application.getId(),
			application.getAppliedAt(),
			null,
			null,
			null,
			null,
			null
		);
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("매칭 점수 입력 직렬화에 실패했습니다.", exception);
		}
	}
}
