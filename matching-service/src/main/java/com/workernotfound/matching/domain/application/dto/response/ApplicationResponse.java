package com.workernotfound.matching.domain.application.dto.response;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import java.time.LocalDateTime;

public record ApplicationResponse(
	Long applicationId,
	Long jobPostId,
	Long workerMemberId,
	ApplicationStatus status,
	LocalDateTime appliedAt,
	LocalDateTime updatedAt,
	Long revision
) {

	public static ApplicationResponse from(Application application) {
		return new ApplicationResponse(
			application.getId(),
			application.getJobPostId(),
			application.getWorkerMemberId(),
			application.getStatus(),
			application.getAppliedAt(),
			application.getUpdatedAt(),
			application.getRevision()
		);
	}
}
