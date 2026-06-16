package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.OAuthConnection;
import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import com.workernotfound.auth.domain.account.entity.enums.SignupType;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.OAuthConnectionRepository;
import com.workernotfound.auth.domain.auth.dto.request.OAuthLoginRequest;
import com.workernotfound.auth.domain.auth.dto.response.OAuthLoginResponse;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import com.workernotfound.auth.external.client.oauth.OAuthProviderClient;
import com.workernotfound.auth.external.client.oauth.OAuthProviderProfile;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Transactional
class OAuthLoginServiceTests extends IntegrationTestSupport {

	@Autowired
	private OAuthLoginService oAuthLoginService;

	@Autowired
	private AuthAccountRepository authAccountRepository;

	@Autowired
	private OAuthConnectionRepository oAuthConnectionRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@MockitoBean
	private OAuthProviderClient oAuthProviderClient;

	@Test
	void loginSucceedsWithExistingOAuthConnection() {
		AuthAccount authAccount = saveOAuthAccount("connected-oauth@example.com");
		oAuthConnectionRepository.save(OAuthConnection.builder()
			.authAccount(authAccount)
			.provider(OAuthProvider.KAKAO)
			.providerUserId("kakao-1")
			.providerEmail(authAccount.getEmail())
			.connectedAt(LocalDateTime.now())
			.build());
		givenProviderProfile("kakao-1", authAccount.getEmail());

		OAuthLoginResponse response = oAuthLoginService.login(OAuthProvider.KAKAO, loginRequest());

		assertThat(response.signupRequired()).isFalse();
		assertThat(response.memberId()).isEqualTo(authAccount.getMemberId());
		assertThat(response.email()).isEqualTo(authAccount.getEmail());
		assertThat(response.role()).isEqualTo(MemberRole.OWNER);
		assertThat(response.tokenResponse().accessToken()).isNotBlank();
		assertThat(response.tokenResponse().refreshToken()).isNotBlank();
		assertThat(refreshTokenRepository.findAllByAuthAccountIdAndRevokedAtIsNull(authAccount.getId())).hasSize(1);
	}

	@Test
	void loginConnectsExistingAccountWithSameEmail() {
		AuthAccount authAccount = saveLocalAccount("same-email@example.com");
		givenProviderProfile("naver-1", authAccount.getEmail());

		OAuthLoginResponse response = oAuthLoginService.login(OAuthProvider.NAVER, loginRequest());

		assertThat(response.signupRequired()).isFalse();
		assertThat(response.memberId()).isEqualTo(authAccount.getMemberId());
		assertThat(oAuthConnectionRepository.findByProviderAndProviderUserId(OAuthProvider.NAVER, "naver-1"))
			.isPresent();
	}

	@Test
	void loginCreatesSignupTicketWhenOAuthConnectionAndEmailDoNotExist() {
		givenProviderProfile("kakao-new", "new-oauth@example.com");

		OAuthLoginResponse response = oAuthLoginService.login(OAuthProvider.KAKAO, loginRequest());

		assertThat(response.signupRequired()).isTrue();
		assertThat(response.signupTicket()).isNotBlank();
		assertThat(response.email()).isEqualTo("new-oauth@example.com");
		assertThat(response.tokenResponse()).isNull();
	}

	private void givenProviderProfile(String providerUserId, String email) {
		when(oAuthProviderClient.getProfile(OAuthProvider.KAKAO, "authorization-code", "http://localhost/callback", "state"))
			.thenReturn(new OAuthProviderProfile(OAuthProvider.KAKAO, providerUserId, email));
		when(oAuthProviderClient.getProfile(OAuthProvider.NAVER, "authorization-code", "http://localhost/callback", "state"))
			.thenReturn(new OAuthProviderProfile(OAuthProvider.NAVER, providerUserId, email));
	}

	private OAuthLoginRequest loginRequest() {
		return new OAuthLoginRequest(
			"authorization-code",
			"http://localhost/callback",
			"state",
			"device-1"
		);
	}

	private AuthAccount saveOAuthAccount(String email) {
		return saveAccount(email, SignupType.OAUTH);
	}

	private AuthAccount saveLocalAccount(String email) {
		return saveAccount(email, SignupType.LOCAL);
	}

	private AuthAccount saveAccount(String email, SignupType signupType) {
		return authAccountRepository.save(AuthAccount.builder()
			.memberId(System.nanoTime())
			.email(email)
			.role(MemberRole.OWNER)
			.signupType(signupType)
			.build());
	}
}
