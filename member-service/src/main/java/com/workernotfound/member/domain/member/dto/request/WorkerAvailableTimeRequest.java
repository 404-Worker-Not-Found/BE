package com.workernotfound.member.domain.member.dto.request;

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
}
