package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(VerificationProperties.class)
public class VerificationService {

	private static final Duration EMAIL_CODE_TTL = Duration.ofMinutes(5);
	private static final Duration SMS_CODE_TTL = Duration.ofMinutes(3);
	private static final Duration VERIFIED_FLAG_TTL = Duration.ofMinutes(30);
	private static final Duration SEND_RATE_LIMIT_TTL = Duration.ofMinutes(1);
	private static final String VERIFIED_VALUE = "true";
	private static final int MAX_VERIFY_ATTEMPTS = 5;

	private final StringRedisTemplate redisTemplate;
	private final VerificationCodeGenerator verificationCodeGenerator;
	private final VerificationCodeHasher verificationCodeHasher;
	private final EmailVerificationSender emailVerificationSender;
	private final SmsVerificationSender smsVerificationSender;

	public void sendEmailVerificationCode(VerificationPurpose purpose, String email) {
		validateSendRateLimit(emailSendRateLimitKey(purpose, email));
		String verificationCode = verificationCodeGenerator.generate();
		saveVerificationCode(emailCodeKey(purpose, email), verificationCode, EMAIL_CODE_TTL);
		emailVerificationSender.send(email, verificationCode);
	}

	public void sendSmsVerificationCode(VerificationPurpose purpose, String phoneNumber) {
		validateSendRateLimit(smsSendRateLimitKey(purpose, phoneNumber));
		String verificationCode = verificationCodeGenerator.generate();
		saveVerificationCode(smsCodeKey(purpose, phoneNumber), verificationCode, SMS_CODE_TTL);
		smsVerificationSender.send(phoneNumber, verificationCode);
	}

	public boolean verifyEmailCode(VerificationPurpose purpose, String email, String verificationCode) {
		boolean verified = verifyCode(
			emailCodeKey(purpose, email),
			emailAttemptKey(purpose, email),
			verificationCode,
			EMAIL_CODE_TTL
		);
		if (verified) {
			saveVerifiedFlag(emailVerifiedKey(purpose, email));
		}
		return verified;
	}

	public boolean verifySmsCode(VerificationPurpose purpose, String phoneNumber, String verificationCode) {
		boolean verified = verifyCode(
			smsCodeKey(purpose, phoneNumber),
			smsAttemptKey(purpose, phoneNumber),
			verificationCode,
			SMS_CODE_TTL
		);
		if (verified) {
			saveVerifiedFlag(smsVerifiedKey(purpose, phoneNumber));
		}
		return verified;
	}

	public boolean isEmailVerified(VerificationPurpose purpose, String email) {
		return VERIFIED_VALUE.equals(redisTemplate.opsForValue().get(emailVerifiedKey(purpose, email)));
	}

	public boolean isSmsVerified(VerificationPurpose purpose, String phoneNumber) {
		return VERIFIED_VALUE.equals(redisTemplate.opsForValue().get(smsVerifiedKey(purpose, phoneNumber)));
	}

	private void saveVerificationCode(String key, String verificationCode, Duration ttl) {
		String hashedCode = verificationCodeHasher.hash(verificationCode);
		redisTemplate.opsForValue().set(key, hashedCode, ttl);
	}

	private boolean verifyCode(String key, String attemptKey, String verificationCode, Duration attemptTtl) {
		String hashedCode = redisTemplate.opsForValue().get(key);
		if (hashedCode == null) {
			return false;
		}
		if (verificationCodeHasher.matches(verificationCode, hashedCode)) {
			redisTemplate.delete(key);
			redisTemplate.delete(attemptKey);
			return true;
		}
		recordFailedAttempt(key, attemptKey, attemptTtl);
		return false;
	}

	private void saveVerifiedFlag(String key) {
		redisTemplate.opsForValue().set(key, VERIFIED_VALUE, VERIFIED_FLAG_TTL);
	}

	private void validateSendRateLimit(String key) {
		Boolean available = redisTemplate.opsForValue().setIfAbsent(key, "1", SEND_RATE_LIMIT_TTL);
		if (!Boolean.TRUE.equals(available)) {
			throw new IllegalArgumentException("인증번호는 1분 후 다시 요청할 수 있습니다.");
		}
	}

	private void recordFailedAttempt(String codeKey, String attemptKey, Duration attemptTtl) {
		Long attempts = redisTemplate.opsForValue().increment(attemptKey);
		if (attempts != null && attempts == 1L) {
			redisTemplate.expire(attemptKey, attemptTtl);
		}
		if (attempts != null && attempts >= MAX_VERIFY_ATTEMPTS) {
			redisTemplate.delete(codeKey);
			redisTemplate.delete(attemptKey);
		}
	}

	private String emailCodeKey(VerificationPurpose purpose, String email) {
		return "auth:verification:email:%s:%s".formatted(purpose, email);
	}

	private String emailVerifiedKey(VerificationPurpose purpose, String email) {
		return "auth:verification:email:verified:%s:%s".formatted(purpose, email);
	}

	private String emailAttemptKey(VerificationPurpose purpose, String email) {
		return "auth:verification:email:attempts:%s:%s".formatted(purpose, email);
	}

	private String emailSendRateLimitKey(VerificationPurpose purpose, String email) {
		return "auth:verification:email:send-limit:%s:%s".formatted(purpose, email);
	}

	private String smsCodeKey(VerificationPurpose purpose, String phoneNumber) {
		return "auth:verification:sms:%s:%s".formatted(purpose, phoneNumber);
	}

	private String smsVerifiedKey(VerificationPurpose purpose, String phoneNumber) {
		return "auth:verification:sms:verified:%s:%s".formatted(purpose, phoneNumber);
	}

	private String smsAttemptKey(VerificationPurpose purpose, String phoneNumber) {
		return "auth:verification:sms:attempts:%s:%s".formatted(purpose, phoneNumber);
	}

	private String smsSendRateLimitKey(VerificationPurpose purpose, String phoneNumber) {
		return "auth:verification:sms:send-limit:%s:%s".formatted(purpose, phoneNumber);
	}
}
