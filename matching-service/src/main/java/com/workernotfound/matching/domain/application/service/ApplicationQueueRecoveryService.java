package com.workernotfound.matching.domain.application.service;

import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.score.service.MatchingScoreQueueService;
import com.workernotfound.matching.external.redis.application.ApplicationQueueLockManager;
import com.workernotfound.matching.external.redis.application.ApplicationQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApplicationQueueRecoveryService {

	private final ApplicationRepository applicationRepository;
	private final ApplicationQueueRepository applicationQueueRepository;
	private final ApplicationQueueLockManager lockManager;
	private final MatchingScoreQueueService matchingScoreQueueService;

	public void recover() {
		applicationRepository.findDistinctJobPostIds()
			.forEach(jobPostId -> lockManager.execute(
				jobPostId,
				fenceToken -> recoverJob(jobPostId, fenceToken)
			));
	}

	private void recoverJob(Long jobPostId, Long fenceToken) {
		applicationQueueRepository.replace(
			jobPostId,
			applicationRepository.findAppliedIdsByJobPostId(jobPostId),
			fenceToken
		);
		matchingScoreQueueService.synchronize(jobPostId, fenceToken);
	}
}
