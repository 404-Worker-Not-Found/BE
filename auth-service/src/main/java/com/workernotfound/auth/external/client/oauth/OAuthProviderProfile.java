package com.workernotfound.auth.external.client.oauth;

import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;

public record OAuthProviderProfile(
	OAuthProvider provider,
	String providerUserId,
	String email
) {
}
