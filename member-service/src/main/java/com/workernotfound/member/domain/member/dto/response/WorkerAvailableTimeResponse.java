package com.workernotfound.member.domain.member.dto.response;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record WorkerAvailableTimeResponse(
	Long availableTimeId,
	DayOfWeek dayOfWeek,
	LocalTime startTime,
	LocalTime endTime
) {
}
