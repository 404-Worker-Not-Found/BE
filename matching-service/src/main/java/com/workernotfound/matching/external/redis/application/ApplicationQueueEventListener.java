package com.workernotfound.matching.external.redis.application;

import com.workernotfound.matching.domain.application.event.ApplicationEvent;
import com.workernotfound.matching.domain.application.event.ApplicationEventType;
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
	private final ApplicationQueueLockManager lockManager;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void updateQueue(ApplicationEvent event) {
		try {
			lockManager.execute(event.jobPostId(), () -> update(event));
		} catch (RuntimeException exception) {
			log.warn(
				"지원 대기열 반영에 실패했습니다. applicationId={}, eventType={}",
				event.applicationId(),
				event.eventType(),
				exception
			);
		}
	}

	private void update(ApplicationEvent event) {
		if (ApplicationEventType.APPLICATION_SUBMITTED.value().equals(event.eventType())) {
			applicationQueueRepository.add(event.jobPostId(), event.applicationId());
			return;
		}
		if (ApplicationEventType.APPLICATION_CANCELED.value().equals(event.eventType())) {
			applicationQueueRepository.remove(event.jobPostId(), event.applicationId());
		}
	}
}
