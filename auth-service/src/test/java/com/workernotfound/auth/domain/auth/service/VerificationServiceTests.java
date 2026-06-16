package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

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
}
