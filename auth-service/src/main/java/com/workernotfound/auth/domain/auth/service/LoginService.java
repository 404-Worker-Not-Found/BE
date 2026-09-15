package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.LocalCredential;
import com.workernotfound.auth.domain.account.entity.enums.MemberStatus;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.LocalCredentialRepository;
import com.workernotfound.auth.domain.auth.dto.request.LoginRequest;
import com.workernotfound.auth.domain.auth.dto.response.LoginResponse;
import com.workernotfound.auth.domain.auth.exception.AuthErrorCode;
import com.workernotfound.auth.domain.token.dto.response.TokenResponse;
import com.workernotfound.auth.domain.token.service.TokenService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoginService {

	private final AuthAccountRepository authAccountRepository;
	private final LocalCredentialRepository localCredentialRepository;
	private final PasswordEncoder passwordEncoder;
	private final TokenService tokenService;

	@Transactional
	public LoginResponse login(LoginRequest request) {
		AuthAccount authAccount =
				authAccountRepository
						.findByEmail(request.email())
						.orElseThrow(() -> new AuthenticationException(AuthErrorCode.INVALID_CREDENTIALS));
		validateActiveAccount(authAccount);

		LocalCredential localCredential =
				localCredentialRepository
						.findByAuthAccount(authAccount)
						.orElseThrow(() -> new AuthenticationException(AuthErrorCode.INVALID_CREDENTIALS));
		validatePassword(request.password(), localCredential);

		authAccount.recordLogin(LocalDateTime.now());
		TokenResponse tokenResponse = tokenService.issue(authAccount, request.deviceId());
		return new LoginResponse(
				authAccount.getMemberId(), authAccount.getEmail(), authAccount.getRole(), tokenResponse);
	}

	private void validateActiveAccount(AuthAccount authAccount) {
		if (authAccount.getStatus() != MemberStatus.ACTIVE) {
			throw new AuthenticationException(AuthErrorCode.ACCOUNT_NOT_ACTIVE);
		}
	}

	private void validatePassword(String rawPassword, LocalCredential localCredential) {
		if (!passwordEncoder.matches(rawPassword, localCredential.getPasswordHash())) {
			throw new AuthenticationException(AuthErrorCode.INVALID_CREDENTIALS);
		}
	}
}
