package com.workernotfound.auth.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class VerificationServiceTests extends IntegrationTestSupport {

	@Autowired private VerificationService verificationService;
	@Autowired private StringRedisTemplate redisTemplate;

	@Test
	void rejectedEmailDeliveryClearsCodeButRetainsRateLimit() {
		String email = "delivery-failure@example.com";
		doThrow(new VerificationDeliveryException("failed"))
			.when(emailVerificationSender).send(org.mockito.ArgumentMatchers.eq(email), org.mockito.ArgumentMatchers.anyString());
		assertThatThrownBy(() -> verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, email))
			.isInstanceOf(VerificationDeliveryException.class);
		assertThat(redisTemplate.hasKey("auth:verification:email:SIGNUP:" + email)).isFalse();
		assertThat(redisTemplate.hasKey("auth:verification:email:send-limit:SIGNUP:" + email)).isTrue();
	}

	@Test
	void rejectedSmsDeliveryClearsCodeButRetainsRateLimit() {
		String phoneNumber = "01011119999";
		doThrow(new VerificationDeliveryException("failed"))
			.when(smsVerificationSender).send(org.mockito.ArgumentMatchers.eq(phoneNumber), org.mockito.ArgumentMatchers.anyString());
		assertThatThrownBy(() -> verificationService.sendSmsVerificationCode(VerificationPurpose.SIGNUP, phoneNumber))
			.isInstanceOf(VerificationDeliveryException.class);
		assertThat(redisTemplate.hasKey("auth:verification:sms:SIGNUP:" + phoneNumber)).isFalse();
		assertThat(redisTemplate.hasKey("auth:verification:sms:send-limit:SIGNUP:" + phoneNumber)).isTrue();
	}

	@Test
	void rejectedEmailResendRestoresPreviousCodeAndExpiry() {
		String email = "previous-email@example.com";
		String key = "auth:verification:email:SIGNUP:" + email;
		redisTemplate.opsForValue().set(key, "previous-hash", Duration.ofMinutes(2));
		doThrow(new VerificationDeliveryException("rejected"))
			.when(emailVerificationSender).send(org.mockito.ArgumentMatchers.eq(email), org.mockito.ArgumentMatchers.anyString());
		assertThatThrownBy(() -> verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, email))
			.isInstanceOf(VerificationDeliveryException.class);
		assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("previous-hash");
		assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 120L);
	}

	@Test
	void rejectedSmsResendRestoresPreviousCodeAndExpiry() {
		String phoneNumber = "01011117777";
		String key = "auth:verification:sms:SIGNUP:" + phoneNumber;
		redisTemplate.opsForValue().set(key, "previous-hash", Duration.ofMinutes(2));
		doThrow(new VerificationDeliveryException("rejected"))
			.when(smsVerificationSender).send(org.mockito.ArgumentMatchers.eq(phoneNumber), org.mockito.ArgumentMatchers.anyString());
		assertThatThrownBy(() -> verificationService.sendSmsVerificationCode(VerificationPurpose.SIGNUP, phoneNumber))
			.isInstanceOf(VerificationDeliveryException.class);
		assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("previous-hash");
		assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 120L);
	}

	@Test
	void uncertainResendKeepsNewCode() {
		String email = "uncertain-resend@example.com";
		String key = "auth:verification:email:SIGNUP:" + email;
		redisTemplate.opsForValue().set(key, "previous-hash", Duration.ofMinutes(2));
		doThrow(new VerificationDeliveryException("timeout", true))
			.when(emailVerificationSender).send(org.mockito.ArgumentMatchers.eq(email), org.mockito.ArgumentMatchers.anyString());
		assertThatThrownBy(() -> verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, email))
			.isInstanceOf(VerificationDeliveryException.class);
		assertThat(redisTemplate.opsForValue().get(key)).isNotEqualTo("previous-hash");
	}

	@Test
	void rejectedResendDoesNotRestoreCodeConsumedDuringSend() {
		String email = "consumed-during-send@example.com";
		String key = "auth:verification:email:SIGNUP:" + email;
		redisTemplate.opsForValue().set(key, "previous-hash", Duration.ofMinutes(2));
		doAnswer(invocation -> {
			redisTemplate.delete(key);
			throw new VerificationDeliveryException("rejected");
		}).when(emailVerificationSender).send(org.mockito.ArgumentMatchers.eq(email), org.mockito.ArgumentMatchers.anyString());
		assertThatThrownBy(() -> verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, email))
			.isInstanceOf(VerificationDeliveryException.class);
		assertThat(redisTemplate.hasKey(key)).isFalse();
	}

	@Test
	void uncertainEmailDeliveryRetainsCodeAndRateLimit() {
		String email = "uncertain-delivery@example.com";
		doThrow(new VerificationDeliveryException("timeout", true))
			.when(emailVerificationSender).send(org.mockito.ArgumentMatchers.eq(email), org.mockito.ArgumentMatchers.anyString());
		assertThatThrownBy(() -> verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, email))
			.isInstanceOf(VerificationDeliveryException.class);
		assertThat(redisTemplate.hasKey("auth:verification:email:SIGNUP:" + email)).isTrue();
		assertThat(redisTemplate.hasKey("auth:verification:email:send-limit:SIGNUP:" + email)).isTrue();
	}

	@Test
	void uncertainSmsDeliveryRetainsCodeAndRateLimit() {
		String phoneNumber = "01011118888";
		doThrow(new VerificationDeliveryException("timeout", true))
			.when(smsVerificationSender).send(org.mockito.ArgumentMatchers.eq(phoneNumber), org.mockito.ArgumentMatchers.anyString());
		assertThatThrownBy(() -> verificationService.sendSmsVerificationCode(VerificationPurpose.SIGNUP, phoneNumber))
			.isInstanceOf(VerificationDeliveryException.class);
		assertThat(redisTemplate.hasKey("auth:verification:sms:SIGNUP:" + phoneNumber)).isTrue();
		assertThat(redisTemplate.hasKey("auth:verification:sms:send-limit:SIGNUP:" + phoneNumber)).isTrue();
	}

	@Test
	void verifyEmailCodeStoresVerifiedFlag() {
		String email = "user@example.com";

		verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, email);

		boolean verified =
				verificationService.verifyEmailCode(VerificationPurpose.SIGNUP, email, "000000");

		assertThat(verified).isFalse();
		assertThat(verificationService.isEmailVerified(VerificationPurpose.SIGNUP, email)).isFalse();
	}

	@Test
	void sendEmailVerificationCodeIsRateLimited() {
		String email = "rate-limit@example.com";
		verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, email);

		assertThatThrownBy(
						() -> verificationService.sendEmailVerificationCode(VerificationPurpose.SIGNUP, email))
				.isInstanceOf(com.workernotfound.auth.global.exception.BusinessException.class)
				.hasMessage("인증번호는 1분 후 다시 요청할 수 있습니다.");
	}

	@Test
	void verifySmsCodeStopsAfterMaxFailedAttempts() {
		String phoneNumber = "01099998888";
		verificationService.sendSmsVerificationCode(VerificationPurpose.SIGNUP, phoneNumber);

		for (int attempt = 0; attempt < 5; attempt++) {
			assertThat(
							verificationService.verifySmsCode(
									VerificationPurpose.SIGNUP, phoneNumber, "invalid-code"))
					.isFalse();
		}

		assertThat(
						verificationService.verifySmsCode(
								VerificationPurpose.SIGNUP, phoneNumber, "invalid-code"))
				.isFalse();
		assertThat(verificationService.isSmsVerified(VerificationPurpose.SIGNUP, phoneNumber))
				.isFalse();
	}
}
