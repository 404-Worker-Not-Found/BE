package com.workernotfound.auth.external.client.member.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record WorkerAvailableTimeRequest(
	DayOfWeek dayOfWeek,
	LocalTime startTime,
	LocalTime endTime
) {
}
