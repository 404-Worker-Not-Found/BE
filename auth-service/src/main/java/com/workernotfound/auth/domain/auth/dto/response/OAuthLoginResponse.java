package com.workernotfound.auth.domain.auth.dto.response;

import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.token.dto.response.TokenResponse;

public record OAuthLoginResponse(
	boolean signupRequired,
	String signupTicket,
	Long memberId,
	String email,
	MemberRole role,
	TokenResponse tokenResponse
) {

	public static OAuthLoginResponse login(Long memberId, String email, MemberRole role, TokenResponse tokenResponse) {
		return new OAuthLoginResponse(false, null, memberId, email, role, tokenResponse);
	}

	public static OAuthLoginResponse signupRequired(String signupTicket, String email) {
		return new OAuthLoginResponse(true, signupTicket, null, email, null, null);
	}
}
