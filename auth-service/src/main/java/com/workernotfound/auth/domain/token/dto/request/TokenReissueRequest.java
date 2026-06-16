package com.workernotfound.auth.domain.token.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TokenReissueRequest(
	@NotBlank
	String refreshToken,

	@NotBlank
	@Size(max = 100)
	String deviceId
) {
}
