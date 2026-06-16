package com.workernotfound.member.domain.member.dto.response;

import java.util.List;

public record WorkerProfileResponse(
	Long workerProfileId,
	Integer desiredHourlyWage,
	Integer activityRadiusKm,
	Boolean immediatelyAvailable,
	LocationResponse baseLocation,
	List<String> preferredBusinessTypes,
	List<WorkerAvailableTimeResponse> availableTimes
) {
}
