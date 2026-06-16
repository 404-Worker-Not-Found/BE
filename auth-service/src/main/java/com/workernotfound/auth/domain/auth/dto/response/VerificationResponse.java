package com.workernotfound.auth.domain.auth.dto.response;

public record VerificationResponse(
	boolean verified,
	String message
) {
}
