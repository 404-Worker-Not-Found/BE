package com.workernotfound.matching.domain.application.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.ApplicationStatusHistory;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationActorType;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.event.ApplicationEvent;
import com.workernotfound.matching.domain.application.exception.ApplicationErrorCode;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.outbox.service.OutboxEventCommandService;
import com.workernotfound.matching.global.exception.BusinessException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplicationCommandService {

	private static final String SUBMITTED_REASON = "APPLICATION_SUBMITTED";
	private static final String CANCELED_REASON = "APPLICATION_CANCELED_BY_WORKER";

	private final ApplicationRepository applicationRepository;
	private final ApplicationStatusHistoryRepository historyRepository;
	private final OutboxEventCommandService outboxEventCommandService;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public Application create(
		Long jobPostId,
		Long workerMemberId,
		Long admissionId,
		LocalDateTime appliedAt,
		String correlationId
	) {
		Application application = Application.builder()
			.jobPostId(jobPostId)
			.workerMemberId(workerMemberId)
			.jobApplicationAdmissionId(admissionId)
			.appliedAt(appliedAt)
			.build();
		applicationRepository.saveAndFlush(application);
		historyRepository.saveAndFlush(initialHistory(application));
		publish(ApplicationEvent.submitted(application, correlationId));
		return application;
	}

	@Transactional
	public Application cancel(Long applicationId, Long workerMemberId, String correlationId) {
		Application application = applicationRepository.findById(applicationId)
			.orElseThrow(() -> new BusinessException(ApplicationErrorCode.APPLICATION_NOT_FOUND));
		validateOwner(application, workerMemberId);
		if (application.getStatus() == ApplicationStatus.CANCELED) {
			return application;
		}
		if (application.getStatus() != ApplicationStatus.APPLIED) {
			throw new BusinessException(ApplicationErrorCode.APPLICATION_STATE_CONFLICT);
		}

		LocalDateTime changedAt = LocalDateTime.now();
		application.cancel();
		applicationRepository.flush();
		historyRepository.saveAndFlush(cancelHistory(application, workerMemberId, changedAt));
		publish(ApplicationEvent.canceled(application, correlationId, changedAt));
		return application;
	}

	private void publish(ApplicationEvent event) {
		outboxEventCommandService.saveApplicationEvent(event);
		eventPublisher.publishEvent(event);
	}

	private ApplicationStatusHistory initialHistory(Application application) {
		return ApplicationStatusHistory.builder()
			.application(application)
			.toStatus(ApplicationStatus.APPLIED)
			.actorType(ApplicationActorType.WORKER)
			.actorMemberId(application.getWorkerMemberId())
			.reasonCode(SUBMITTED_REASON)
			.revision(application.getRevision())
			.changedAt(application.getAppliedAt())
			.build();
	}

	private ApplicationStatusHistory cancelHistory(
		Application application,
		Long workerMemberId,
		LocalDateTime changedAt
	) {
		return ApplicationStatusHistory.builder()
			.application(application)
			.fromStatus(ApplicationStatus.APPLIED)
			.toStatus(ApplicationStatus.CANCELED)
			.actorType(ApplicationActorType.WORKER)
			.actorMemberId(workerMemberId)
			.reasonCode(CANCELED_REASON)
			.revision(application.getRevision())
			.changedAt(changedAt)
			.build();
	}

	private void validateOwner(Application application, Long workerMemberId) {
		if (!application.getWorkerMemberId().equals(workerMemberId)) {
			throw new BusinessException(ApplicationErrorCode.APPLICATION_FORBIDDEN);
		}
	}
}
