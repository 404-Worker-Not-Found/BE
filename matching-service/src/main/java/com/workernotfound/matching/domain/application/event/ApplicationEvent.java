package com.workernotfound.matching.domain.application.event;

import com.workernotfound.matching.domain.application.entity.Application;
import java.time.LocalDateTime;
import java.util.UUID;

public record ApplicationEvent(
	String eventId,
	String eventType,
	LocalDateTime occurredAt,
	Long aggregateId,
	String correlationId,
	Long revision,
	Integer version,
	Long applicationId,
	Long admissionId,
	Long jobPostId,
	Long workerMemberId,
	String status,
	LocalDateTime statusChangedAt
) {

	private static final int SCHEMA_VERSION = 1;

	public static ApplicationEvent submitted(
		Application application,
		String correlationId
	) {
		return from(
			application,
			ApplicationEventType.APPLICATION_SUBMITTED,
			correlationId,
			application.getAppliedAt()
		);
	}

	public static ApplicationEvent canceled(
		Application application,
		String correlationId,
		LocalDateTime changedAt
	) {
		return from(application, ApplicationEventType.APPLICATION_CANCELED, correlationId, changedAt);
	}

	private static ApplicationEvent from(
		Application application,
		ApplicationEventType eventType,
		String correlationId,
		LocalDateTime changedAt
	) {
		return new ApplicationEvent(
			UUID.randomUUID().toString(),
			eventType.value(),
			changedAt,
			application.getId(),
			correlationId,
			application.getRevision(),
			SCHEMA_VERSION,
			application.getId(),
			application.getJobApplicationAdmissionId(),
			application.getJobPostId(),
			application.getWorkerMemberId(),
			application.getStatus().name(),
			changedAt
		);
	}
}
