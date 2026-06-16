package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifySmsRequest(
	@NotBlank
	@Size(max = 20)
	String phoneNumber,

	@NotBlank
	@Size(max = 20)
	String verificationCode
) {
}
