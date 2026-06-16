package com.workernotfound.auth.external.client.oauth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.oauth2")
public record OAuth2ClientProperties(
	Duration connectTimeout,
	Duration readTimeout,
	OAuth2ProviderConfig kakao,
	OAuth2ProviderConfig naver
) {

	public OAuth2ProviderConfig kakaoConfig() {
		return kakao;
	}

	public OAuth2ProviderConfig naverConfig() {
		return naver;
	}
}
