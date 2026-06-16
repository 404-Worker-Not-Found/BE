package com.workernotfound.auth.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OAuthLoginRequest(
	@NotBlank
	String authorizationCode,

	@NotBlank
	String redirectUri,

	@Size(max = 255)
	String state,

	@NotBlank
	@Size(max = 100)
	String deviceId
) {
}
