package com.workernotfound.auth.external.client.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class RestOAuthProviderClient implements OAuthProviderClient {

	private static final String AUTHORIZATION_CODE = "authorization_code";
	private static final String BEARER_PREFIX = "Bearer ";

	private final RestClient oauthProviderRestClient;
	private final OAuth2ClientProperties properties;

	@Override
	public OAuthProviderProfile getProfile(
		OAuthProvider provider,
		String authorizationCode,
		String redirectUri,
		String state
	) {
		OAuth2ProviderConfig providerConfig = providerConfig(provider);
		validateConfig(provider, providerConfig);

		try {
			String accessToken = requestAccessToken(provider, providerConfig, authorizationCode, redirectUri, state);
			JsonNode userInfo = requestUserInfo(providerConfig, accessToken);
			return parseProfile(provider, userInfo);
		} catch (RestClientException exception) {
			throw new OAuth2ClientException(exception);
		}
	}

	private String requestAccessToken(
		OAuthProvider provider,
		OAuth2ProviderConfig providerConfig,
		String authorizationCode,
		String redirectUri,
		String state
	) {
		JsonNode response = oauthProviderRestClient.post()
			.uri(providerConfig.tokenUri())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(tokenRequest(provider, providerConfig, authorizationCode, redirectUri, state))
			.retrieve()
			.body(JsonNode.class);
		String accessToken = response == null ? null : response.path("access_token").asText(null);
		if (!StringUtils.hasText(accessToken)) {
			throw new OAuth2ClientException("OAuth2 access token 응답이 올바르지 않습니다.");
		}
		return accessToken;
	}

	private JsonNode requestUserInfo(OAuth2ProviderConfig providerConfig, String accessToken) {
		JsonNode response = oauthProviderRestClient.get()
			.uri(providerConfig.userInfoUri())
			.header("Authorization", BEARER_PREFIX + accessToken)
			.retrieve()
			.body(JsonNode.class);
		if (response == null) {
			throw new OAuth2ClientException("OAuth2 user info 응답이 비어 있습니다.");
		}
		return response;
	}

	private MultiValueMap<String, String> tokenRequest(
		OAuthProvider provider,
		OAuth2ProviderConfig providerConfig,
		String authorizationCode,
		String redirectUri,
		String state
	) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("grant_type", AUTHORIZATION_CODE);
		values.put("client_id", providerConfig.clientId());
		values.put("code", authorizationCode);
		values.put("redirect_uri", redirectUri);
		if (StringUtils.hasText(providerConfig.clientSecret())) {
			values.put("client_secret", providerConfig.clientSecret());
		}
		if (provider == OAuthProvider.NAVER && StringUtils.hasText(state)) {
			values.put("state", state);
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		values.forEach(form::add);
		return form;
	}

	private OAuthProviderProfile parseProfile(OAuthProvider provider, JsonNode userInfo) {
		if (provider == OAuthProvider.KAKAO) {
			return parseKakaoProfile(userInfo);
		}
		if (provider == OAuthProvider.NAVER) {
			return parseNaverProfile(userInfo);
		}
		throw new OAuth2ClientException("지원하지 않는 OAuth2 provider입니다.");
	}

	private OAuthProviderProfile parseKakaoProfile(JsonNode userInfo) {
		String providerUserId = userInfo.path("id").asText(null);
		String email = userInfo.path("kakao_account").path("email").asText(null);
		return validateProfile(new OAuthProviderProfile(OAuthProvider.KAKAO, providerUserId, email));
	}

	private OAuthProviderProfile parseNaverProfile(JsonNode userInfo) {
		JsonNode response = userInfo.path("response");
		String providerUserId = response.path("id").asText(null);
		String email = response.path("email").asText(null);
		return validateProfile(new OAuthProviderProfile(OAuthProvider.NAVER, providerUserId, email));
	}

	private OAuthProviderProfile validateProfile(OAuthProviderProfile profile) {
		if (!StringUtils.hasText(profile.providerUserId()) || !StringUtils.hasText(profile.email())) {
			throw new OAuth2ClientException("OAuth2 provider 프로필에 필수 정보가 없습니다.");
		}
		return profile;
	}

	private OAuth2ProviderConfig providerConfig(OAuthProvider provider) {
		if (provider == OAuthProvider.KAKAO) {
			return properties.kakaoConfig();
		}
		if (provider == OAuthProvider.NAVER) {
			return properties.naverConfig();
		}
		throw new OAuth2ClientException("지원하지 않는 OAuth2 provider입니다.");
	}

	private void validateConfig(OAuthProvider provider, OAuth2ProviderConfig providerConfig) {
		if (providerConfig == null || !StringUtils.hasText(providerConfig.clientId())) {
			throw new OAuth2ClientException("%s OAuth2 client 설정이 없습니다.".formatted(provider));
		}
		if (provider == OAuthProvider.NAVER && !StringUtils.hasText(providerConfig.clientSecret())) {
			throw new OAuth2ClientException("NAVER OAuth2 client secret 설정이 없습니다.");
		}
	}
}
