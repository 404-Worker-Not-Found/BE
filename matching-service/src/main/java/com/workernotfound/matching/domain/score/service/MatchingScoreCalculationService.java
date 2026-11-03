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
import com.workernotfound.matching.domain.score.policy.TrustWeightedScorePolicy;
import com.workernotfound.matching.domain.score.repository.MemberTrustScoreRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingScoreCalculationService {

	private static final List<ScoreInputType> UNAVAILABLE_INPUTS = List.of(
		ScoreInputType.INDUSTRY_EXPERIENCE,
		ScoreInputType.ONLINE_STATUS,
		ScoreInputType.EXPECTED_ARRIVAL_TIME,
		ScoreInputType.NO_SHOW_RISK
	);

	private final ApplicationRepository applicationRepository;
	private final MatchingScoreBatchRepository batchRepository;
	private final MatchingScoreSnapshotRepository snapshotRepository;
	private final TrustWeightedScorePolicy scorePolicy;
	private final MemberTrustScoreRepository trustScoreRepository;
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
			.policyVersion(TrustWeightedScorePolicy.VERSION)
			.startedAt(startedAt)
			.build());
	}

	private void saveSnapshots(
		MatchingScoreBatch batch,
		List<Application> applications,
		LocalDateTime calculatedAt
	) {
		Map<Long, BigDecimal> trustScores = trustScoreRepository.findTrustScores(
			applications.stream().map(Application::getWorkerMemberId).toList()
		);
		for (int position = 0; position < applications.size(); position++) {
			Application application = applications.get(position);
			BigDecimal trustScore = trustScores.get(application.getWorkerMemberId());
			MatchingScoreSnapshot snapshot = pendingSnapshot(batch, application, trustScore, calculatedAt);
			snapshot.complete(scorePolicy.calculate(position, applications.size(), trustScore), calculatedAt);
			snapshotRepository.save(snapshot);
		}
		snapshotRepository.flush();
	}

	private MatchingScoreSnapshot pendingSnapshot(
		MatchingScoreBatch batch,
		Application application,
		BigDecimal trustScore,
		LocalDateTime calculatedAt
	) {
		return MatchingScoreSnapshot.builder()
			.scoreBatch(batch)
			.application(application)
			.inputSnapshot(toJson(scoreInput(application, trustScore)))
			.missingInputs(toJson(missingInputs(trustScore)))
			.calculatedAt(calculatedAt)
			.build();
	}

	private List<ScoreInputType> missingInputs(BigDecimal trustScore) {
		List<ScoreInputType> missingInputs = new ArrayList<>();
		if (trustScore == null) {
			missingInputs.add(ScoreInputType.RATING);
		}
		missingInputs.addAll(UNAVAILABLE_INPUTS);
		return missingInputs;
	}

	// 평점 입력(rating)에는 member-service가 받은 리뷰 평점으로 계산한 신뢰 점수(0~100)를 넣는다.
	private MatchingScoreInput scoreInput(Application application, BigDecimal trustScore) {
		return new MatchingScoreInput(
			application.getId(),
			application.getAppliedAt(),
			trustScore,
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
