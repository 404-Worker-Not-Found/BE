package com.workernotfound.matching.domain.application.dto.response;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;

public record ApplicationStateResponse(
	Long applicationId,
	ApplicationStatus status,
	Long revision
) {

	public static ApplicationStateResponse from(Application application) {
		return new ApplicationStateResponse(application.getId(), application.getStatus(), application.getRevision());
	}
}
