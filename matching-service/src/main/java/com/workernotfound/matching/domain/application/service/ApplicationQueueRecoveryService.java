package com.workernotfound.matching.domain.application.service;

import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.external.redis.application.ApplicationQueueLockManager;
import com.workernotfound.matching.external.redis.application.ApplicationQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplicationQueueRecoveryService {

	private final ApplicationRepository applicationRepository;
	private final ApplicationQueueRepository applicationQueueRepository;
	private final ApplicationQueueLockManager lockManager;

	@Transactional(readOnly = true)
	public void recover() {
		applicationRepository.findDistinctJobPostIds()
			.forEach(jobPostId -> lockManager.execute(jobPostId, () -> recoverJob(jobPostId)));
	}

	private void recoverJob(Long jobPostId) {
		applicationQueueRepository.replace(
			jobPostId,
			applicationRepository.findAppliedIdsByJobPostId(jobPostId)
		);
	}
}
