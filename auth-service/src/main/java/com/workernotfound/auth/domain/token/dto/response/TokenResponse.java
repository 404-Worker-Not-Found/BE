package com.workernotfound.auth.domain.token.dto.response;

public record TokenResponse(
	String accessToken,
	String refreshToken,
	String tokenType,
	Long accessTokenExpiresIn,
	Long refreshTokenExpiresIn
) {
}
