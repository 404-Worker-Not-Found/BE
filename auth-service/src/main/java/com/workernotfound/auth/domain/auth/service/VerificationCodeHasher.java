package com.workernotfound.auth.domain.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class VerificationCodeHasher {

	private static final String HASH_ALGORITHM = "SHA-256";

	public String hash(String verificationCode) {
		try {
			MessageDigest messageDigest = MessageDigest.getInstance(HASH_ALGORITHM);
			byte[] digest = messageDigest.digest(verificationCode.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("인증번호 해시 알고리즘을 사용할 수 없습니다.", exception);
		}
	}

	public boolean matches(String rawCode, String hashedCode) {
		return hash(rawCode).equals(hashedCode);
	}
}
