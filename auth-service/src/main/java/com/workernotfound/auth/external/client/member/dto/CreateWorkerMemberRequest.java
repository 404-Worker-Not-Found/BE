package com.workernotfound.auth.external.client.member.dto;

import java.util.List;

public record CreateWorkerMemberRequest(
	String name,
	String email,
	String phoneNumber,
	MemberRole role,
	Integer desiredHourlyWage,
	Integer activityRadiusKm,
	Boolean immediatelyAvailable,
	LocationRequest baseLocation,
	List<String> preferredBusinessTypes,
	List<WorkerAvailableTimeRequest> availableTimes
) {
}
