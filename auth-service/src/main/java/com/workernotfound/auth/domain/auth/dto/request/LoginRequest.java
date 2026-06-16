package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
	@NotBlank
	@Email
	@Size(max = 255)
	String email,

	@NotBlank
	String password,

	@NotBlank
	@Size(max = 100)
	String deviceId
) {
}
