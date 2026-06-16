package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendEmailVerificationRequest(
	@NotBlank
	@Email
	@Size(max = 255)
	String email
) {
}
