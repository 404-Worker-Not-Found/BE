package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.LocalCredential;
import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.SignupType;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.LocalCredentialRepository;
import com.workernotfound.auth.domain.auth.dto.request.LoginRequest;
import com.workernotfound.auth.domain.auth.dto.response.LoginResponse;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class LoginServiceTests extends IntegrationTestSupport {

	@Autowired
	private LoginService loginService;

	@Autowired
	private AuthAccountRepository authAccountRepository;

	@Autowired
	private LocalCredentialRepository localCredentialRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void loginSucceedsWithLocalCredential() {
		AuthAccount authAccount = saveLocalAccount("login@example.com", "password1234");

		LoginResponse response = loginService.login(new LoginRequest(
			"login@example.com",
			"password1234",
			"device-1"
		));

		assertThat(response.memberId()).isEqualTo(authAccount.getMemberId());
		assertThat(response.email()).isEqualTo(authAccount.getEmail());
		assertThat(response.role()).isEqualTo(MemberRole.OWNER);
		assertThat(response.tokenResponse().accessToken()).isNotBlank();
		assertThat(response.tokenResponse().refreshToken()).isNotBlank();
		assertThat(authAccount.getLastLoginAt()).isNotNull();
		assertThat(refreshTokenRepository.findAllByAuthAccountIdAndRevokedAtIsNull(authAccount.getId())).hasSize(1);
	}

	@Test
	void loginFailsWithWrongPassword() {
		saveLocalAccount("wrong-password@example.com", "password1234");

		assertThatThrownBy(() -> loginService.login(new LoginRequest(
			"wrong-password@example.com",
			"wrong-password",
			"device-1"
		)))
			.isInstanceOf(AuthenticationException.class)
			.hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
	}

	@Test
	void loginFailsWithUnknownEmail() {
		assertThatThrownBy(() -> loginService.login(new LoginRequest(
			"unknown@example.com",
			"password1234",
			"device-1"
		)))
			.isInstanceOf(AuthenticationException.class)
			.hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
	}

	private AuthAccount saveLocalAccount(String email, String rawPassword) {
		AuthAccount authAccount = authAccountRepository.save(AuthAccount.builder()
			.memberId(System.nanoTime())
			.email(email)
			.role(MemberRole.OWNER)
			.signupType(SignupType.LOCAL)
			.build());
		localCredentialRepository.save(LocalCredential.builder()
			.authAccount(authAccount)
			.passwordHash(passwordEncoder.encode(rawPassword))
			.passwordChangedAt(LocalDateTime.now())
			.build());
		return authAccount;
	}
}
