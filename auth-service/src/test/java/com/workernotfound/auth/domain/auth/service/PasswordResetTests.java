package com.workernotfound.auth.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.LocalCredential;
import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.SignupType;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.account.repository.LocalCredentialRepository;
import com.workernotfound.auth.domain.auth.dto.request.LoginRequest;
import com.workernotfound.auth.domain.auth.dto.request.PasswordResetRequest;
import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.domain.auth.exception.AuthErrorCode;
import com.workernotfound.auth.domain.token.dto.request.TokenReissueRequest;
import com.workernotfound.auth.domain.token.dto.response.TokenResponse;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import com.workernotfound.auth.domain.token.service.RefreshTokenException;
import com.workernotfound.auth.domain.token.service.TokenService;
import com.workernotfound.auth.global.exception.BusinessException;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class PasswordResetTests extends IntegrationTestSupport {

	@Autowired private PasswordResetService passwordResetService;
	@Autowired private VerificationService verificationService;
	@Autowired private AuthAccountRepository accountRepository;
	@Autowired private com.workernotfound.auth.domain.account.repository.OAuthConnectionRepository connectionRepository;
	@Autowired private LocalCredentialRepository credentialRepository;
	@Autowired private RefreshTokenRepository refreshTokenRepository;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private TokenService tokenService;
	@Autowired private LoginService loginService;
	@Autowired private StringRedisTemplate redisTemplate;
	@Autowired private MockMvc mockMvc;
	@Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

	@Test
	void publicApiResetsPasswordAndRevokesEveryDevice() throws Exception {
		AuthAccount account = createAccount(true);
		TokenResponse first = tokenService.issue(account, "first");
		TokenResponse second = tokenService.issue(account, "second");
		AtomicReference<String> code = captureCode(account.getEmail());
		mockMvc.perform(post("/api/auth/password-resets/email-verifications/send")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(account.getEmail())))
				.andExpect(status().isOk());
		LocalDateTime before = credentialRepository.findByAuthAccountId(account.getId())
				.orElseThrow().getPasswordChangedAt();
		mockMvc.perform(post("/api/auth/password-resets")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"verificationCode\":\"%s\",\"newPassword\":\"new-password123\"}"
						.formatted(account.getEmail(), code.get())))
				.andExpect(status().isOk());
		LocalCredential credential = credentialRepository.findByAuthAccountId(account.getId()).orElseThrow();
		assertThat(passwordEncoder.matches("new-password123", credential.getPasswordHash())).isTrue();
		assertThat(credential.getPasswordChangedAt()).isAfter(before);
		assertThat(refreshTokenRepository.findAllByAuthAccountIdAndRevokedAtIsNull(account.getId())).isEmpty();
		assertRevoked(first, "first");
		assertRevoked(second, "second");
		assertThatThrownBy(() -> loginService.login(new LoginRequest(account.getEmail(), "old-password123", "first")))
				.isInstanceOf(AuthenticationException.class);
		assertThat(loginService.login(new LoginRequest(account.getEmail(), "new-password123", "first"))
				.tokenResponse().refreshToken()).isNotBlank();
		assertInvalidReset(account.getEmail(), code.get());
	}

	@Test
	void signupCodeAndVerifiedFlagCannotAuthorizeReset() {
		AuthAccount account = createAccount(true);
		AtomicReference<String> code = captureCode(account.getEmail());
		verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, account.getEmail());
		assertInvalidReset(account.getEmail(), code.get());
		assertThat(verificationService.verifyEmailCode(VerificationPurpose.SIGNUP, account.getEmail(), code.get())).isTrue();
		assertInvalidReset(account.getEmail(), code.get());
		assertOriginalPassword(account);
	}

	@Test
	void fiveWrongAttemptsInvalidateResetCode() {
		AuthAccount account = createAccount(true);
		String code = sendCode(account);
		String wrongCode = code.equals("000000") ? "111111" : "000000";
		for (int i = 0; i < 5; i++) {
			assertInvalidReset(account.getEmail(), wrongCode);
		}
		assertInvalidReset(account.getEmail(), code);
		assertOriginalPassword(account);
	}

	@Test
	void expiredCodeCannotResetPassword() {
		AuthAccount account = createAccount(true);
		String code = sendCode(account);
		redisTemplate.delete("auth:verification:email:PASSWORD_RESET:" + account.getEmail());
		assertInvalidReset(account.getEmail(), code);
		assertOriginalPassword(account);
	}

	@Test
	void unavailableAccountsReceiveSameSendResponseWithoutDelivery() throws Exception {
		AuthAccount oauth = createAccount(false);
		AuthAccount blocked = createAccount(true);
		blocked.block();
		accountRepository.save(blocked);
		AuthAccount withdrawn = createAccount(true);
		withdrawn.withdraw();
		accountRepository.save(withdrawn);
		for (String email : java.util.List.of(oauth.getEmail(), blocked.getEmail(), withdrawn.getEmail(), "missing@example.com")) {
			mockMvc.perform(post("/api/auth/password-resets/email-verifications/send")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"%s\"}".formatted(email)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("data.message").value("재설정 가능한 계정이면 이메일 인증번호를 발송했습니다."));
			verify(emailVerificationSender, never()).send(eq(email), anyString());
			assertInvalidReset(email, "123456");
		}
		assertThat(credentialRepository.findByAuthAccountId(oauth.getId())).isEmpty();
	}

	@Test
	void accountBlockedAfterSendingCannotResetPassword() {
		AuthAccount account = createAccount(true);
		String code = sendCode(account);
		account.block();
		accountRepository.save(account);
		assertInvalidReset(account.getEmail(), code);
		assertOriginalPassword(account);
	}

	@Test
	void sendRetainsRateLimitAndDeliveryErrorContract() throws Exception {
		AuthAccount account = createAccount(true);
		sendCode(account);
		mockMvc.perform(post("/api/auth/password-resets/email-verifications/send")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(account.getEmail())))
				.andExpect(status().isTooManyRequests());
		AuthAccount failed = createAccount(true);
		org.mockito.Mockito.doThrow(new VerificationDeliveryException("발송 실패"))
				.when(emailVerificationSender).send(eq(failed.getEmail()), anyString());
		mockMvc.perform(post("/api/auth/password-resets/email-verifications/send")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(failed.getEmail())))
				.andExpect(status().isBadGateway());
	}

	@Test
	void invalidPayloadAndOversizedUtf8PasswordDoNotConsumeCode() throws Exception {
		mockMvc.perform(post("/api/auth/password-resets").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		AuthAccount account = createAccount(true);
		String code = sendCode(account);
		assertThatThrownBy(() -> passwordResetService.resetPassword(
				new PasswordResetRequest(account.getEmail(), code, "가".repeat(25))))
				.isInstanceOf(BusinessException.class);
		passwordResetService.resetPassword(new PasswordResetRequest(account.getEmail(), code, "new-password123"));
	}

	@Test
	void concurrentConsumptionAllowsOnlyOneRequest() throws Exception {
		AuthAccount account = createAccount(true);
		String code = sendCode(account);
		var executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			var first = executor.submit(() -> { start.await(); return verificationService.consumePasswordResetCode(account.getEmail(), code); });
			var second = executor.submit(() -> { start.await(); return verificationService.consumePasswordResetCode(account.getEmail(), code); });
			start.countDown();
			assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(true, false);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void reissueWaitingForResetCannotCreateSurvivingSession() throws Exception {
		AuthAccount account = createAccount(true);
		TokenResponse token = tokenService.issue(account, "first");
		String code = sendCode(account);
		CountDownLatch resetApplied = new CountDownLatch(1);
		CountDownLatch allowCommit = new CountDownLatch(1);
		CountDownLatch reissueStarted = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var reset = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				passwordResetService.resetPassword(new PasswordResetRequest(account.getEmail(), code, "new-password123"));
				resetApplied.countDown();
				try {
					if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Commit timed out");
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
			}));
			assertThat(resetApplied.await(10, TimeUnit.SECONDS)).isTrue();
			var reissue = executor.submit(() -> {
				reissueStarted.countDown();
				assertRevoked(token, "first");
			});
			assertThat(reissueStarted.await(10, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> reissue.get(200, TimeUnit.MILLISECONDS))
					.isInstanceOf(java.util.concurrent.TimeoutException.class);
			allowCommit.countDown();
			reset.get(10, TimeUnit.SECONDS);
			reissue.get(10, TimeUnit.SECONDS);
			assertThat(refreshTokenRepository.findAllByAuthAccountIdAndRevokedAtIsNull(account.getId())).isEmpty();
		} finally {
			allowCommit.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void rollbackLeavesPasswordAndSessionsUnchangedButCodeConsumed() {
		AuthAccount account = createAccount(true);
		tokenService.issue(account, "first");
		String code = sendCode(account);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			passwordResetService.resetPassword(new PasswordResetRequest(account.getEmail(), code, "new-password123"));
			status.setRollbackOnly();
		});
		assertOriginalPassword(account);
		assertThat(refreshTokenRepository.findAllByAuthAccountIdAndRevokedAtIsNull(account.getId())).hasSize(1);
		assertInvalidReset(account.getEmail(), code);
	}

	@Test
	void resetDoesNotRemoveLinkedOAuthConnection() {
		AuthAccount account = createAccount(true);
		var connection = connectionRepository.save(com.workernotfound.auth.domain.account.entity.OAuthConnection.builder()
				.authAccount(account).provider(com.workernotfound.auth.domain.account.entity.enums.OAuthProvider.KAKAO)
				.providerUserId("reset-" + account.getId()).providerEmail(account.getEmail())
				.connectedAt(LocalDateTime.now()).build());
		String code = sendCode(account);
		passwordResetService.resetPassword(new PasswordResetRequest(account.getEmail(), code, "new-password123"));
		assertThat(connectionRepository.findById(connection.getId())).isPresent();
	}

	private AuthAccount createAccount(boolean hasCredential) {
		long id = System.nanoTime();
		AuthAccount account = accountRepository.save(AuthAccount.builder().memberId(id)
				.email("reset-" + id + "@example.com").role(MemberRole.WORKER)
				.signupType(hasCredential ? SignupType.LOCAL : SignupType.OAUTH).build());
		if (hasCredential) {
			credentialRepository.save(LocalCredential.builder().authAccount(account)
					.passwordHash(passwordEncoder.encode("old-password123"))
					.passwordChangedAt(LocalDateTime.now().minusDays(1)).build());
		}
		return account;
	}

	private AtomicReference<String> captureCode(String email) {
		AtomicReference<String> code = new AtomicReference<>();
		doAnswer(invocation -> { code.set(invocation.getArgument(1)); return null; })
				.when(emailVerificationSender).send(eq(email), anyString());
		return code;
	}

	private String sendCode(AuthAccount account) {
		AtomicReference<String> code = captureCode(account.getEmail());
		passwordResetService.sendVerificationCode(account.getEmail());
		return code.get();
	}

	private void assertOriginalPassword(AuthAccount account) {
		assertThat(passwordEncoder.matches("old-password123",
				credentialRepository.findByAuthAccountId(account.getId()).orElseThrow().getPasswordHash())).isTrue();
	}

	private void assertInvalidReset(String email, String code) {
		assertThatThrownBy(() -> passwordResetService.resetPassword(new PasswordResetRequest(email, code, "new-password123")))
				.isInstanceOfSatisfying(BusinessException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_PASSWORD_RESET));
	}

	private void assertRevoked(TokenResponse token, String device) {
		assertThatThrownBy(() -> tokenService.reissue(new TokenReissueRequest(token.refreshToken(), device)))
				.isInstanceOf(RefreshTokenException.class).hasMessage("이미 폐기된 refresh token입니다.");
	}
}
