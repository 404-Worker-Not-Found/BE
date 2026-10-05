package com.workernotfound.auth.domain.auth.service;

import com.workernotfound.auth.domain.auth.entity.enums.VerificationPurpose;
import com.workernotfound.auth.domain.auth.exception.AuthErrorCode;
import com.workernotfound.auth.global.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VerificationService {

	private static final Duration EMAIL_CODE_TTL = Duration.ofMinutes(5);
	private static final Duration SMS_CODE_TTL = Duration.ofMinutes(3);
	private static final Duration VERIFIED_FLAG_TTL = Duration.ofMinutes(30);
	private static final Duration SEND_RATE_LIMIT_TTL = Duration.ofMinutes(1);
	private static final String VERIFIED_VALUE = "true";
	private static final int MAX_VERIFY_ATTEMPTS = 5;
	private static final DefaultRedisScript<String> REPLACE_CODE_SCRIPT = new DefaultRedisScript<>("""
		local previous = redis.call('GET', KEYS[1]) or ''
		local expiry = redis.call('PEXPIRETIME', KEYS[1])
		redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
		return previous .. ':' .. expiry
		""", String.class);
	private static final DefaultRedisScript<Long> RESTORE_CODE_SCRIPT = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
		if ARGV[2] ~= '' and tonumber(ARGV[3]) > 0 then
		  redis.call('SET', KEYS[1], ARGV[2], 'PXAT', ARGV[3])
		else
		  redis.call('DEL', KEYS[1])
		end
		return 1
		""", Long.class);

	private static final DefaultRedisScript<Long> CONSUME_RESET_CODE_SCRIPT = new DefaultRedisScript<>("""
		local code = redis.call('GET', KEYS[1])
		if not code then return 0 end
		if code == ARGV[1] then
		  redis.call('DEL', KEYS[1], KEYS[2])
		  return 1
		end
		local attempts = redis.call('INCR', KEYS[2])
		if attempts == 1 then redis.call('PEXPIRE', KEYS[2], redis.call('PTTL', KEYS[1])) end
		if attempts >= tonumber(ARGV[2]) then redis.call('DEL', KEYS[1], KEYS[2]) end
		return 0
		""", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final VerificationCodeGenerator verificationCodeGenerator;
	private final VerificationCodeHasher verificationCodeHasher;
	private final EmailVerificationSender emailVerificationSender;
	private final SmsVerificationSender smsVerificationSender;

	public void sendEmailVerificationCode(VerificationPurpose purpose, String email) {
		validateSendRateLimit(emailSendRateLimitKey(purpose, email));
		sendEmailCode(purpose, email);
	}

	void reservePasswordResetEmailSend(String email) {
		validateSendRateLimit(emailSendRateLimitKey(VerificationPurpose.PASSWORD_RESET, email));
	}

	void sendPasswordResetEmailCode(String email) {
		sendEmailCode(VerificationPurpose.PASSWORD_RESET, email);
	}

	private void sendEmailCode(VerificationPurpose purpose, String email) {
		String codeKey = emailCodeKey(purpose, email);
		String verificationCode = verificationCodeGenerator.generate();
		VerificationCodeReplacement replacement = replaceVerificationCode(codeKey, verificationCode, EMAIL_CODE_TTL);
		try {
			emailVerificationSender.send(email, verificationCode);
		} catch (VerificationDeliveryException exception) {
			if (!exception.isDeliveryUncertain()) {
				restoreVerificationCode(codeKey, replacement);
			}
			throw exception;
		}
	}

	public void sendSmsVerificationCode(VerificationPurpose purpose, String phoneNumber) {
		String limitKey = smsSendRateLimitKey(purpose, phoneNumber);
		String codeKey = smsCodeKey(purpose, phoneNumber);
		validateSendRateLimit(limitKey);
		String verificationCode = verificationCodeGenerator.generate();
		VerificationCodeReplacement replacement = replaceVerificationCode(codeKey, verificationCode, SMS_CODE_TTL);
		try {
			smsVerificationSender.send(phoneNumber, verificationCode);
		} catch (VerificationDeliveryException exception) {
			if (!exception.isDeliveryUncertain()) {
				restoreVerificationCode(codeKey, replacement);
			}
			throw exception;
		}
	}

	public boolean verifyEmailCode(
			VerificationPurpose purpose, String email, String verificationCode) {
		boolean verified =
				verifyCode(
						emailCodeKey(purpose, email),
						emailAttemptKey(purpose, email),
						verificationCode,
						EMAIL_CODE_TTL);
		if (verified) {
			saveVerifiedFlag(emailVerifiedKey(purpose, email));
		}
		return verified;
	}

	public boolean verifySmsCode(
			VerificationPurpose purpose, String phoneNumber, String verificationCode) {
		boolean verified =
				verifyCode(
						smsCodeKey(purpose, phoneNumber),
						smsAttemptKey(purpose, phoneNumber),
						verificationCode,
						SMS_CODE_TTL);
		if (verified) {
			saveVerifiedFlag(smsVerifiedKey(purpose, phoneNumber));
		}
		return verified;
	}

	public boolean consumePasswordResetCode(String email, String verificationCode) {
		Long result = redisTemplate.execute(CONSUME_RESET_CODE_SCRIPT,
				List.of(emailCodeKey(VerificationPurpose.PASSWORD_RESET, email),
						emailAttemptKey(VerificationPurpose.PASSWORD_RESET, email)),
				verificationCodeHasher.hash(verificationCode), Integer.toString(MAX_VERIFY_ATTEMPTS));
		return Long.valueOf(1).equals(result);
	}

    public boolean consumeContactCode(boolean email, String target, String code) {
        String codeKey = email ? emailCodeKey(VerificationPurpose.CONTACT_CHANGE, target)
                : smsCodeKey(VerificationPurpose.CONTACT_CHANGE, target);
        String attempts = email ? emailAttemptKey(VerificationPurpose.CONTACT_CHANGE, target)
                : smsAttemptKey(VerificationPurpose.CONTACT_CHANGE, target);
        Long result = redisTemplate.execute(CONSUME_RESET_CODE_SCRIPT, List.of(codeKey, attempts),
                verificationCodeHasher.hash(code), Integer.toString(MAX_VERIFY_ATTEMPTS));
        return Long.valueOf(1).equals(result);
    }

    public void eraseAccountVerification(String email) {
        if (email == null) return;
        for (VerificationPurpose purpose : VerificationPurpose.values()) {
            redisTemplate.delete(List.of(emailCodeKey(purpose,email), emailVerifiedKey(purpose,email),
                    emailAttemptKey(purpose,email), emailSendRateLimitKey(purpose,email)));
        }
    }

	public boolean isEmailVerified(VerificationPurpose purpose, String email) {
		return VERIFIED_VALUE.equals(redisTemplate.opsForValue().get(emailVerifiedKey(purpose, email)));
	}

	public boolean isSmsVerified(VerificationPurpose purpose, String phoneNumber) {
		return VERIFIED_VALUE.equals(
				redisTemplate.opsForValue().get(smsVerifiedKey(purpose, phoneNumber)));
	}

	private VerificationCodeReplacement replaceVerificationCode(String key, String verificationCode, Duration ttl) {
		String newHash = verificationCodeHasher.hash(verificationCode);
		String snapshot = redisTemplate.execute(REPLACE_CODE_SCRIPT, List.of(key), newHash, Long.toString(ttl.toMillis()));
		if (snapshot == null) {
			throw new IllegalStateException("인증번호 저장 결과가 없습니다.");
		}
		int separator = snapshot.lastIndexOf(':');
		return new VerificationCodeReplacement(newHash, snapshot.substring(0, separator), snapshot.substring(separator + 1));
	}

	private void restoreVerificationCode(String key, VerificationCodeReplacement replacement) {
		redisTemplate.execute(RESTORE_CODE_SCRIPT, List.of(key),
			replacement.newHash(), replacement.previousHash(), replacement.previousExpiresAt());
	}

	private record VerificationCodeReplacement(String newHash, String previousHash, String previousExpiresAt) {}

	private boolean verifyCode(
			String key, String attemptKey, String verificationCode, Duration attemptTtl) {
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
			throw new BusinessException(AuthErrorCode.VERIFICATION_RATE_LIMITED);
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
