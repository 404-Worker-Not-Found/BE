package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.OAuthConnection;
import com.workernotfound.auth.domain.account.entity.enums.MemberStatus;
import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.OAuthConnectionRepository;
import com.workernotfound.auth.domain.auth.dto.request.OAuthLoginRequest;
import com.workernotfound.auth.domain.auth.dto.response.OAuthLoginResponse;
import com.workernotfound.auth.domain.token.dto.response.TokenResponse;
import com.workernotfound.auth.domain.token.service.TokenService;
import com.workernotfound.auth.external.client.oauth.OAuthProviderClient;
import com.workernotfound.auth.external.client.oauth.OAuthProviderProfile;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OAuthLoginService {

	private final OAuthProviderClient oAuthProviderClient;
	private final OAuthConnectionRepository oAuthConnectionRepository;
	private final AuthAccountRepository authAccountRepository;
	private final OAuthSignupTicketService oAuthSignupTicketService;
	private final TokenService tokenService;

	@Transactional
	public OAuthLoginResponse login(OAuthProvider provider, OAuthLoginRequest request) {
		OAuthProviderProfile profile = oAuthProviderClient.getProfile(
			provider,
			request.authorizationCode(),
			request.redirectUri(),
			request.state()
		);

		return oAuthConnectionRepository
			.findByProviderAndProviderUserId(provider, profile.providerUserId())
			.map(connection -> loginConnectedAccount(connection, request.deviceId()))
			.orElseGet(() -> connectOrCreateSignupTicket(provider, profile, request.deviceId()));
	}

	private OAuthLoginResponse loginConnectedAccount(OAuthConnection connection, String deviceId) {
		AuthAccount authAccount = connection.getAuthAccount();
		validateActiveAccount(authAccount);
		authAccount.recordLogin(LocalDateTime.now());
		TokenResponse tokenResponse = tokenService.issue(authAccount, deviceId);
		return OAuthLoginResponse.login(
			authAccount.getMemberId(),
			authAccount.getEmail(),
			authAccount.getRole(),
			tokenResponse
		);
	}

	private OAuthLoginResponse connectOrCreateSignupTicket(
		OAuthProvider provider,
		OAuthProviderProfile profile,
		String deviceId
	) {
		return authAccountRepository.findByEmail(profile.email())
			.map(authAccount -> connectAndLogin(provider, profile, authAccount, deviceId))
			.orElseGet(() -> createSignupTicket(provider, profile));
	}

	private OAuthLoginResponse connectAndLogin(
		OAuthProvider provider,
		OAuthProviderProfile profile,
		AuthAccount authAccount,
		String deviceId
	) {
		validateActiveAccount(authAccount);
		oAuthConnectionRepository.save(OAuthConnection.builder()
			.authAccount(authAccount)
			.provider(provider)
			.providerUserId(profile.providerUserId())
			.providerEmail(profile.email())
			.connectedAt(LocalDateTime.now())
			.build());
		authAccount.recordLogin(LocalDateTime.now());
		TokenResponse tokenResponse = tokenService.issue(authAccount, deviceId);
		return OAuthLoginResponse.login(
			authAccount.getMemberId(),
			authAccount.getEmail(),
			authAccount.getRole(),
			tokenResponse
		);
	}

	private OAuthLoginResponse createSignupTicket(OAuthProvider provider, OAuthProviderProfile profile) {
		String ticket = oAuthSignupTicketService.save(new OAuthSignupTicket(
			provider,
			profile.providerUserId(),
			profile.email(),
			LocalDateTime.now()
		));
		return OAuthLoginResponse.signupRequired(ticket, profile.email());
	}

	private void validateActiveAccount(AuthAccount authAccount) {
		if (authAccount.getStatus() != MemberStatus.ACTIVE) {
			throw new AuthenticationException("활성 상태의 계정만 로그인할 수 있습니다.");
		}
	}
}
