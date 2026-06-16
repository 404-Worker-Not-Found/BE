package com.workernotfound.auth.domain.token.dto.request;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
	@NotBlank
	String refreshToken
) {
}
