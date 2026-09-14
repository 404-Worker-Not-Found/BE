package com.workernotfound.matching.external.client.confirmation.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public record ScheduledWorkRequest(
	Long matchingId,
	Long jobPostId,
	Long ownerMemberId,
	Long workerMemberId,
	String paymentId,
	LocalDate workDate,
	LocalTime startTime,
	LocalTime endTime
) {
}
