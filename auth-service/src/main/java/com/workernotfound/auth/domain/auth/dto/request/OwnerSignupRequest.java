package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OwnerSignupRequest(
	@NotBlank
	@Size(max = 50)
	String name,

	@NotBlank
	@Email
	@Size(max = 255)
	String email,

	@NotBlank
	@Size(min = 8, max = 100)
	String password,

	@NotBlank
	@Size(max = 20)
	String phoneNumber,

	@NotBlank
	@Size(max = 20)
	String businessRegistrationNumber,

	@NotBlank
	@Size(max = 100)
	String businessType,

	@Valid
	@NotNull
	LocationRequest storeLocation
) {
}
