package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalTime;

public record WorkerAvailableTimeRequest(
	@NotNull
	DayOfWeek dayOfWeek,

	@NotNull
	LocalTime startTime,

	@NotNull
	LocalTime endTime
) {
	// TODO: Validate startTime < endTime in the service layer.
}
