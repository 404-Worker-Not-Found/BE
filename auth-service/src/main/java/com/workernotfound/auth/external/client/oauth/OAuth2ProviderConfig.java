package com.workernotfound.auth.external.client.oauth;

public record OAuth2ProviderConfig(
	String clientId,
	String clientSecret,
	String tokenUri,
	String userInfoUri
) {
}
