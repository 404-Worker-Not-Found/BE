package com.workernotfound.member.global.security;

import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtTokenVerifier {

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private final JwtProperties jwtProperties;

	private byte[] secretKey;

	@PostConstruct
	void initialize() {
		this.secretKey = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
	}

	public boolean validateAccessToken(String token) {
		try {
			Map<String, String> claims = parseAndVerify(token);
			long expiresAt = readLongClaim(claims, "exp");
			return Instant.now().getEpochSecond() < expiresAt;
		} catch (RuntimeException exception) {
			return false;
		}
	}

	public AuthenticatedMember parseAccessToken(String token) {
		Map<String, String> claims = parseAndVerify(token);
		long expiresAt = readLongClaim(claims, "exp");
		if (Instant.now().getEpochSecond() >= expiresAt) {
			throw new IllegalArgumentException("만료된 access token입니다.");
		}
		return new AuthenticatedMember(
			readLongClaim(claims, "authAccountId"),
			readLongClaim(claims, "memberId"),
			MemberRole.valueOf(readStringClaim(claims, "role"))
		);
	}

	private Map<String, String> parseAndVerify(String token) {
		String[] tokenParts = token.split("\\.");
		if (tokenParts.length != 3) {
			throw new IllegalArgumentException("JWT 형식이 올바르지 않습니다.");
		}
		String unsignedToken = "%s.%s".formatted(tokenParts[0], tokenParts[1]);
		String expectedSignature = sign(unsignedToken);
		if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), tokenParts[2].getBytes(StandardCharsets.UTF_8))) {
			throw new IllegalArgumentException("JWT 서명이 올바르지 않습니다.");
		}
		return decodeClaims(tokenParts[1]);
	}

	private Map<String, String> decodeClaims(String encodedPayload) {
		try {
			String json = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
			return parseFlatJson(json);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("JWT payload를 읽을 수 없습니다.", exception);
		}
	}

	private String sign(String value) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
			byte[] signature = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
		} catch (Exception exception) {
			throw new IllegalStateException("JWT 서명 생성에 실패했습니다.", exception);
		}
	}

	private Map<String, String> parseFlatJson(String json) {
		if (!json.startsWith("{") || !json.endsWith("}")) {
			throw new IllegalArgumentException("JWT payload JSON 형식이 올바르지 않습니다.");
		}
		Map<String, String> values = new LinkedHashMap<>();
		String body = json.substring(1, json.length() - 1);
		for (String entry : body.split(",")) {
			String[] keyValue = entry.split(":", 2);
			if (keyValue.length != 2) {
				throw new IllegalArgumentException("JWT payload entry 형식이 올바르지 않습니다.");
			}
			values.put(trimQuotes(keyValue[0]), trimQuotes(keyValue[1]));
		}
		return values;
	}

	private long readLongClaim(Map<String, String> claims, String claimName) {
		String value = claims.get(claimName);
		if (value != null) {
			return Long.parseLong(value);
		}
		throw new IllegalArgumentException("JWT claim을 읽을 수 없습니다: " + claimName);
	}

	private String readStringClaim(Map<String, String> claims, String claimName) {
		String value = claims.get(claimName);
		if (value != null) {
			return value;
		}
		throw new IllegalArgumentException("JWT claim을 읽을 수 없습니다: " + claimName);
	}

	private String trimQuotes(String value) {
		String trimmed = value.trim();
		if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed;
	}
}
