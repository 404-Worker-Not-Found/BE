package com.workernotfound.member.domain.member.dto.request;

import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateWorkerMemberRequest(
	@NotBlank
	@Size(max = 50)
	String name,

	@NotBlank
	@Email
	@Size(max = 255)
	String email,

	@NotBlank
	@Pattern(regexp = "^\\+?[0-9]{10,15}$")
	String phoneNumber,

	@NotNull
	MemberRole role,

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
