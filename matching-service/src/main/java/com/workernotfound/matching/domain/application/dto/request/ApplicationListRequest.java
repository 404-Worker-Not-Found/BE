package com.workernotfound.matching.domain.application.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record ApplicationListRequest(
	@PositiveOrZero
	Integer page,

	@Positive
	@Max(100)
	Integer size
) {
}
