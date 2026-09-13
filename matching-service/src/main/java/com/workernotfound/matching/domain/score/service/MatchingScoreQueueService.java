package com.workernotfound.matching.domain.score.service;

import com.workernotfound.matching.domain.score.model.MatchingScoreQueueProjection;
import com.workernotfound.matching.external.redis.score.MatchingScoreQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MatchingScoreQueueService {

	private final MatchingScoreQueuePreparationService preparationService;
	private final MatchingScoreQueueRepository scoreQueueRepository;

	public void synchronize(Long jobPostId, Long fenceToken) {
		preparationService.prepare(jobPostId).ifPresentOrElse(
			projection -> replaceQueue(projection, fenceToken),
			() -> scoreQueueRepository.clear(jobPostId, fenceToken)
		);
	}

	private void replaceQueue(MatchingScoreQueueProjection projection, Long fenceToken) {
		scoreQueueRepository.replace(
			projection.jobPostId(),
			projection.scoreBatchId(),
			projection.policyVersion(),
			projection.scores(),
			fenceToken
		);
	}
}
