package com.workernotfound.matching.domain.application.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.ApplicationStatusHistory;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationActorType;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.event.ApplicationEvent;
import com.workernotfound.matching.domain.application.event.RecruitmentCompletionEvent;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.repository.RecruitmentStateRepository;
import com.workernotfound.matching.domain.matching.service.MatchingCancellationService;
import com.workernotfound.matching.domain.outbox.service.OutboxEventCommandService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecruitmentCompletionService {

	private static final String RECRUITMENT_COMPLETED_REASON = "RECRUITMENT_COMPLETED";

	private final ApplicationRepository applicationRepository;
	private final ApplicationStatusHistoryRepository historyRepository;
	private final RecruitmentStateRepository recruitmentStateRepository;
	private final MatchingCancellationService matchingCancellationService;
	private final OutboxEventCommandService outboxEventCommandService;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public void complete(Long jobPostId, Long jobVersion, String correlationId) {
		recruitmentStateRepository.ensureExists(jobPostId);
		var recruitmentState = recruitmentStateRepository.findForUpdate(jobPostId).orElseThrow();
		LocalDateTime changedAt = LocalDateTime.now();
		if (!recruitmentState.complete(jobVersion, correlationId, changedAt)) {
			return;
		}
		List<Application> applications = applicationRepository.findAllForUpdate(
			jobPostId,
			ApplicationStatus.APPLIED
		);
		for (Application application : applications) {
			reject(application, correlationId, changedAt);
		}
		eventPublisher.publishEvent(new RecruitmentCompletionEvent(jobPostId));
	}

	private void reject(Application application, String correlationId, LocalDateTime changedAt) {
		matchingCancellationService.cancelPendingForRecruitmentCompletion(application.getId(), changedAt);
		application.reject();
		applicationRepository.flush();
		historyRepository.saveAndFlush(rejectedHistory(application, changedAt));
		ApplicationEvent event = ApplicationEvent.rejected(application, correlationId, changedAt);
		outboxEventCommandService.saveApplicationEvent(event);
	}

	private ApplicationStatusHistory rejectedHistory(Application application, LocalDateTime changedAt) {
		return ApplicationStatusHistory.builder()
			.application(application)
			.fromStatus(ApplicationStatus.APPLIED)
			.toStatus(ApplicationStatus.REJECTED)
			.actorType(ApplicationActorType.SYSTEM)
			.reasonCode(RECRUITMENT_COMPLETED_REASON)
			.revision(application.getRevision())
			.changedAt(changedAt)
			.build();
	}
}
