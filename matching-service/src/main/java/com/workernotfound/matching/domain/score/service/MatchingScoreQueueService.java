package com.workernotfound.matching.domain.score.service;

import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.model.RankedApplicationScore;
import com.workernotfound.matching.domain.score.policy.ApplicationTimeScorePolicy;
import com.workernotfound.matching.external.redis.score.MatchingScoreQueueRepository;
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
public class MatchingScoreQueueService {

	private final ApplicationRepository applicationRepository;
	private final MatchingScoreCalculationService calculationService;
	private final MatchingScoreFindService findService;
	private final MatchingScoreQueueRepository scoreQueueRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void synchronize(Long jobPostId) {
		List<Long> activeApplicationIds = applicationRepository.findAppliedIdsByJobPostId(jobPostId);
		if (activeApplicationIds.isEmpty()) {
			scoreQueueRepository.clear(jobPostId);
			return;
		}

		Optional<MatchingScoreBatch> latestBatch = findService.findLatestReadyBatch(jobPostId);
		if (latestBatch.isEmpty() || needsRecalculation(latestBatch.get(), activeApplicationIds)) {
			recalculate(jobPostId);
			return;
		}
		replaceQueue(latestBatch.get());
	}

	private void recalculate(Long jobPostId) {
		calculationService.calculate(jobPostId)
			.ifPresentOrElse(this::replaceQueue, () -> scoreQueueRepository.clear(jobPostId));
	}

	private boolean needsRecalculation(
		MatchingScoreBatch batch,
		List<Long> activeApplicationIds
	) {
		if (!ApplicationTimeScorePolicy.VERSION.equals(batch.getPolicyVersion())) {
			return true;
		}
		List<RankedApplicationScore> scores = findService.findRankedScores(batch.getId());
		return !new HashSet<>(activeApplicationIds).equals(applicationIds(scores));
	}

	private Set<Long> applicationIds(List<RankedApplicationScore> scores) {
		Set<Long> applicationIds = new HashSet<>();
		scores.forEach(score -> applicationIds.add(score.applicationId()));
		return applicationIds;
	}

	private void replaceQueue(MatchingScoreBatch batch) {
		scoreQueueRepository.replace(
			batch.getJobPostId(),
			batch.getId(),
			batch.getPolicyVersion(),
			findService.findRankedScores(batch.getId())
		);
	}
}
