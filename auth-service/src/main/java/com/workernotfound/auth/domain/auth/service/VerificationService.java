package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VerificationService {

	private static final Duration EMAIL_CODE_TTL = Duration.ofMinutes(5);
	private static final Duration SMS_CODE_TTL = Duration.ofMinutes(3);
	private static final Duration VERIFIED_FLAG_TTL = Duration.ofMinutes(30);
	private static final String VERIFIED_VALUE = "true";

	private final StringRedisTemplate redisTemplate;
	private final VerificationCodeGenerator verificationCodeGenerator;
	private final VerificationCodeHasher verificationCodeHasher;
	private final EmailVerificationSender emailVerificationSender;
	private final SmsVerificationSender smsVerificationSender;

	public void sendEmailVerificationCode(VerificationPurpose purpose, String email) {
		// TODO: Add rate limit before generating and sending verification codes.
		String verificationCode = verificationCodeGenerator.generate();
		saveVerificationCode(emailCodeKey(purpose, email), verificationCode, EMAIL_CODE_TTL);
		emailVerificationSender.send(email, verificationCode);
	}

	public void sendSmsVerificationCode(VerificationPurpose purpose, String phoneNumber) {
		// TODO: Add rate limit before generating and sending verification codes.
		String verificationCode = verificationCodeGenerator.generate();
		saveVerificationCode(smsCodeKey(purpose, phoneNumber), verificationCode, SMS_CODE_TTL);
		smsVerificationSender.send(phoneNumber, verificationCode);
	}

	public boolean verifyEmailCode(VerificationPurpose purpose, String email, String verificationCode) {
		boolean verified = verifyCode(emailCodeKey(purpose, email), verificationCode);
		if (verified) {
			saveVerifiedFlag(emailVerifiedKey(purpose, email));
		}
		return verified;
	}

	public boolean verifySmsCode(VerificationPurpose purpose, String phoneNumber, String verificationCode) {
		boolean verified = verifyCode(smsCodeKey(purpose, phoneNumber), verificationCode);
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

	private boolean verifyCode(String key, String verificationCode) {
		String hashedCode = redisTemplate.opsForValue().get(key);
		if (hashedCode == null) {
			return false;
		}
		return verificationCodeHasher.matches(verificationCode, hashedCode);
	}

	private void saveVerifiedFlag(String key) {
		redisTemplate.opsForValue().set(key, VERIFIED_VALUE, VERIFIED_FLAG_TTL);
	}

	private String emailCodeKey(VerificationPurpose purpose, String email) {
		return "auth:verification:email:%s:%s".formatted(purpose, email);
	}

	private String emailVerifiedKey(VerificationPurpose purpose, String email) {
		return "auth:verification:email:verified:%s:%s".formatted(purpose, email);
	}

	private String smsCodeKey(VerificationPurpose purpose, String phoneNumber) {
		return "auth:verification:sms:%s:%s".formatted(purpose, phoneNumber);
	}

	private String smsVerifiedKey(VerificationPurpose purpose, String phoneNumber) {
		return "auth:verification:sms:verified:%s:%s".formatted(purpose, phoneNumber);
	}
}
