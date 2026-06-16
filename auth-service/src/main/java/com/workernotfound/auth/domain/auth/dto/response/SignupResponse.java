package com.workernotfound.auth.domain.auth.dto.response;

import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.token.dto.response.TokenResponse;

public record SignupResponse(
	Long memberId,
	String email,
	MemberRole role,
	TokenResponse tokenResponse
) {
}
