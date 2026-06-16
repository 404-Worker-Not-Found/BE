package com.workernotfound.auth.external.client.oauth;

import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;

public interface OAuthProviderClient {

	OAuthProviderProfile getProfile(
		OAuthProvider provider,
		String authorizationCode,
		String redirectUri,
		String state
	);
}
