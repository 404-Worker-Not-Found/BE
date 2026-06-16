package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record OAuthWorkerSignupRequest(
	@NotBlank
	@Size(max = 100)
	String signupTicket,

	@NotBlank
	@Size(max = 50)
	String name,

	@NotBlank
	@Size(max = 20)
	String phoneNumber,

	@NotBlank
	@Size(max = 100)
	String deviceId,

	@NotNull
	@PositiveOrZero
	Integer desiredHourlyWage,

	@NotNull
	@Positive
	Integer activityRadiusKm,

	@NotNull
	Boolean immediatelyAvailable,

	@Valid
	@NotNull
	LocationRequest baseLocation,

	@NotEmpty
	@Size(max = 20)
	List<@NotBlank @Size(max = 100) String> preferredBusinessTypes,

	@Valid
	@NotEmpty
	@Size(max = 50)
	List<WorkerAvailableTimeRequest> availableTimes
) {
}
