package com.workernotfound.matching.domain.score.service;

import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.model.MatchingScoreQueueProjection;
import com.workernotfound.matching.domain.score.policy.ApplicationTimeScorePolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingScoreQueuePreparationService {

	private final ApplicationRepository applicationRepository;
	private final MatchingScoreCalculationService calculationService;
	private final MatchingScoreFindService findService;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<MatchingScoreQueueProjection> prepare(Long jobPostId) {
		List<Long> activeApplicationIds = applicationRepository.findAppliedIdsByJobPostId(jobPostId);
		if (activeApplicationIds.isEmpty()) {
			return Optional.empty();
		}

		MatchingScoreBatch batch = currentBatch(jobPostId, activeApplicationIds);
		return Optional.of(toProjection(batch));
	}

	private MatchingScoreBatch currentBatch(
		Long jobPostId,
		List<Long> activeApplicationIds
	) {
		Optional<MatchingScoreBatch> latestBatch = findService.findLatestReadyBatch(jobPostId);
		if (latestBatch.isPresent() && isCurrent(latestBatch.get(), activeApplicationIds)) {
			return latestBatch.get();
		}
		return calculationService.calculate(jobPostId).orElseThrow();
	}

	private boolean isCurrent(MatchingScoreBatch batch, List<Long> activeApplicationIds) {
		if (!ApplicationTimeScorePolicy.VERSION.equals(batch.getPolicyVersion())) {
			return false;
		}
		Set<Long> scoredApplicationIds = new HashSet<>(
			findService.findReadyApplicationIds(batch.getId())
		);
		return new HashSet<>(activeApplicationIds).equals(scoredApplicationIds);
	}

	private MatchingScoreQueueProjection toProjection(MatchingScoreBatch batch) {
		return new MatchingScoreQueueProjection(
			batch.getJobPostId(),
			batch.getId(),
			batch.getPolicyVersion(),
			findService.findRankedScores(batch.getId())
		);
	}
}
