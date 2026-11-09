package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OAuthOwnerSignupRequest(
	@NotBlank
	@Size(max = 100)
	String signupTicket,

	@NotBlank
	@Size(max = 50)
	String name,

	@NotBlank
	@Pattern(regexp = "^\\+?[0-9]{10,15}$")
	String phoneNumber,

	@NotBlank
	@Size(max = 100)
	String deviceId,

	@NotBlank
	@Pattern(regexp = "^[0-9]{10}$|^[0-9]{3}-[0-9]{2}-[0-9]{5}$")
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
