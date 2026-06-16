package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerificationServiceTests extends IntegrationTestSupport {

	@Autowired
	private VerificationService verificationService;

	@Test
	void verifyEmailCodeStoresVerifiedFlag() {
		String email = "user@example.com";

		verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, email);

		boolean verified = verificationService.verifyEmailCode(VerificationPurpose.SIGNUP, email, "000000");

		assertThat(verified).isFalse();
		assertThat(verificationService.isEmailVerified(VerificationPurpose.SIGNUP, email)).isFalse();
	}

	@Test
	void sendEmailVerificationCodeIsRateLimited() {
		String email = "rate-limit@example.com";
		verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, email);

		assertThatThrownBy(() -> verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, email))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("인증번호는 1분 후 다시 요청할 수 있습니다.");
	}

	@Test
	void verifySmsCodeStopsAfterMaxFailedAttempts() {
		String phoneNumber = "01099998888";
		verificationService.sendSmsVerificationCode(VerificationPurpose.SIGNUP, phoneNumber);

		for (int attempt = 0; attempt < 5; attempt++) {
			assertThat(verificationService.verifySmsCode(VerificationPurpose.SIGNUP, phoneNumber, "invalid-code")).isFalse();
		}

		assertThat(verificationService.verifySmsCode(VerificationPurpose.SIGNUP, phoneNumber, "invalid-code")).isFalse();
		assertThat(verificationService.isSmsVerified(VerificationPurpose.SIGNUP, phoneNumber)).isFalse();
	}
}
