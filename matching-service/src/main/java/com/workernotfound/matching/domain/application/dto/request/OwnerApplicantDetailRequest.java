package com.workernotfound.matching.domain.application.dto.request;

import jakarta.validation.constraints.Positive;

public record OwnerApplicantDetailRequest(
	@Positive
	Long scoreBatchId
) {
}
