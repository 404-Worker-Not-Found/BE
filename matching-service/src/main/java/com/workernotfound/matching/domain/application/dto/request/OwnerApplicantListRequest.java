package com.workernotfound.matching.domain.application.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record OwnerApplicantListRequest(
	@Positive
	Long scoreBatchId,

	@PositiveOrZero
	Integer page,

	@Positive
	@Max(100)
	Integer size
) {
}
