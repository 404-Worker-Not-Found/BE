package com.workernotfound.matching.external.redis.application;

import com.workernotfound.matching.domain.application.event.ApplicationEvent;
import com.workernotfound.matching.domain.application.event.ApplicationEventType;
import com.workernotfound.matching.domain.application.event.RecruitmentCompletionEvent;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.score.service.MatchingScoreQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationQueueEventListener {

	private final ApplicationQueueRepository applicationQueueRepository;
	private final ApplicationRepository applicationRepository;
	private final ApplicationQueueLockManager lockManager;
	private final MatchingScoreQueueService matchingScoreQueueService;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void updateQueue(ApplicationEvent event) {
		try {
			lockManager.execute(event.jobPostId(), fenceToken -> update(event, fenceToken));
		} catch (RuntimeException exception) {
			log.warn(
				"지원 대기열 반영에 실패했습니다. applicationId={}, eventType={}",
				event.applicationId(),
				event.eventType(),
				exception
			);
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void rebuildQueue(RecruitmentCompletionEvent event) {
		try {
			lockManager.execute(event.jobPostId(), fenceToken -> rebuild(event.jobPostId(), fenceToken));
		} catch (RuntimeException exception) {
			log.warn("모집 완료 후 지원 대기열 재구성에 실패했습니다. jobPostId={}", event.jobPostId(), exception);
		}
	}

	private void rebuild(Long jobPostId, Long fenceToken) {
		applicationQueueRepository.replace(jobPostId, applicationRepository.findAppliedIdsByJobPostId(jobPostId));
		matchingScoreQueueService.synchronize(jobPostId, fenceToken);
	}

	private void update(ApplicationEvent event, Long fenceToken) {
		if (ApplicationEventType.APPLICATION_SUBMITTED.value().equals(event.eventType())) {
			applicationQueueRepository.add(event.jobPostId(), event.applicationId());
		} else {
			applicationQueueRepository.remove(event.jobPostId(), event.applicationId());
		}
		matchingScoreQueueService.synchronize(event.jobPostId(), fenceToken);
	}
}
