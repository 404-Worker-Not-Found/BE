package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import java.time.LocalDateTime;

public record OAuthSignupTicket(
	OAuthProvider provider,
	String providerUserId,
	String providerEmail,
	LocalDateTime issuedAt
) {
}
