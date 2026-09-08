package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OAuthOwnerSignupRequest(
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

	@NotBlank
	@Size(max = 20)
	String businessRegistrationNumber,

	@NotBlank
	@Size(max = 100)
	String storeName,

	@NotBlank
	@Size(max = 100)
	String businessType,

	@Valid
	@NotNull
	LocationRequest storeLocation
) {
}
