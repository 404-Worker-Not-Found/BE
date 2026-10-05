package com.workernotfound.auth.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.LocalCredential;
import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.SignupType;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.LocalCredentialRepository;
import com.workernotfound.auth.domain.auth.dto.request.PasswordResetRequest;
import com.workernotfound.auth.domain.auth.exception.AuthErrorCode;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import com.workernotfound.auth.global.exception.BusinessException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTests {

	@Mock private AuthAccountRepository accountRepository;
	@Mock private LocalCredentialRepository credentialRepository;
	@Mock private RefreshTokenRepository refreshTokenRepository;
	@Mock private VerificationService verificationService;
	@Mock private PasswordEncoder passwordEncoder;
	@InjectMocks private PasswordResetService passwordResetService;

	@Test
	void invalidCodeDoesNotPerformPasswordEncodingOrSessionRevocation() {
		String email = "reset@example.com";
		AuthAccount account = AuthAccount.builder().memberId(1L).email(email)
				.role(MemberRole.WORKER).signupType(SignupType.LOCAL).build();
		when(accountRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(account));
		when(credentialRepository.findByAuthAccount(account)).thenReturn(Optional.of(
				LocalCredential.builder().authAccount(account).passwordHash("existing-hash").build()));
		when(verificationService.consumePasswordResetCode(email, "123456")).thenReturn(false);

		assertThatThrownBy(() -> passwordResetService.resetPassword(
				new PasswordResetRequest(email, "123456", "new-password123")))
				.isInstanceOf(BusinessException.class);

		verifyNoInteractions(passwordEncoder, refreshTokenRepository);
	}

	@Test
	void rateLimitedRequestDoesNotQueryAccountOrCredential() {
		String email = "reset@example.com";
		doThrow(new BusinessException(AuthErrorCode.VERIFICATION_RATE_LIMITED))
				.when(verificationService).reservePasswordResetEmailSend(email);

		assertThatThrownBy(() -> passwordResetService.sendVerificationCode(email))
				.isInstanceOf(BusinessException.class);

		verifyNoInteractions(accountRepository, credentialRepository);
	}
}
