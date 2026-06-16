package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendSmsVerificationRequest(
	@NotBlank
	@Size(max = 20)
	String phoneNumber
) {
}
