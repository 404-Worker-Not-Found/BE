package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.LocalCredential;
import com.workernotfound.auth.domain.account.entity.OAuthConnection;
import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.SignupType;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.LocalCredentialRepository;
import com.workernotfound.auth.domain.account.repository.OAuthConnectionRepository;
import com.workernotfound.auth.domain.auth.dto.response.SignupResponse;
import com.workernotfound.auth.domain.token.dto.response.TokenResponse;
import com.workernotfound.auth.domain.token.service.TokenService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SignupPersistenceService {

	private final AuthAccountRepository authAccountRepository;
	private final LocalCredentialRepository localCredentialRepository;
	private final OAuthConnectionRepository oAuthConnectionRepository;
	private final PasswordEncoder passwordEncoder;
	private final TokenService tokenService;

	@Transactional
	public SignupResponse saveLocalAccountAndIssueToken(
		Long memberId,
		String email,
		MemberRole role,
		String rawPassword,
		String deviceId
	) {
		validateEmailAvailable(email);
		AuthAccount authAccount = authAccountRepository.save(AuthAccount.builder()
			.memberId(memberId)
			.email(email)
			.role(role)
			.signupType(SignupType.LOCAL)
			.build());
		saveLocalCredential(authAccount, rawPassword);

		TokenResponse tokenResponse = tokenService.issue(authAccount, deviceId);
		return new SignupResponse(memberId, email, role, tokenResponse);
	}

	@Transactional
	public SignupResponse saveOAuthAccountAndIssueToken(
		Long memberId,
		OAuthSignupTicket signupTicket,
		MemberRole role,
		String deviceId
	) {
		validateOAuthAccountAvailable(signupTicket);
		AuthAccount authAccount = authAccountRepository.save(AuthAccount.builder()
			.memberId(memberId)
			.email(signupTicket.providerEmail())
			.role(role)
			.signupType(SignupType.OAUTH)
			.build());
		oAuthConnectionRepository.save(OAuthConnection.builder()
			.authAccount(authAccount)
			.provider(signupTicket.provider())
			.providerUserId(signupTicket.providerUserId())
			.providerEmail(signupTicket.providerEmail())
			.connectedAt(LocalDateTime.now())
			.build());

		TokenResponse tokenResponse = tokenService.issue(authAccount, deviceId);
		return new SignupResponse(memberId, signupTicket.providerEmail(), role, tokenResponse);
	}

	private void validateEmailAvailable(String email) {
		if (authAccountRepository.existsByEmail(email)) {
			throw new SignupException("이미 가입된 이메일입니다.");
		}
	}

	private void validateOAuthAccountAvailable(OAuthSignupTicket signupTicket) {
		validateEmailAvailable(signupTicket.providerEmail());
		if (oAuthConnectionRepository.existsByProviderAndProviderUserId(
			signupTicket.provider(),
			signupTicket.providerUserId()
		)) {
			throw new SignupException("이미 연결된 OAuth 계정입니다.");
		}
	}

	private void saveLocalCredential(AuthAccount authAccount, String rawPassword) {
		LocalCredential localCredential = LocalCredential.builder()
			.authAccount(authAccount)
			.passwordHash(passwordEncoder.encode(rawPassword))
			.passwordChangedAt(LocalDateTime.now())
			.build();
		localCredentialRepository.save(localCredential);
	}
}
