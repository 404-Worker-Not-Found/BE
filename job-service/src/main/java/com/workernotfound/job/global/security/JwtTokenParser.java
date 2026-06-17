package com.workernotfound.job.global.security;

import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.global.exception.GlobalErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(JwtProperties.class)
public class JwtTokenParser {

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
        } catch (RuntimeException e) {
            return false;
        }
    }

    public MemberClaims parseAccessToken(String token) {
        Map<String, String> claims = parseAndVerify(token);
        long expiresAt = readLongClaim(claims, "exp");
        if (Instant.now().getEpochSecond() >= expiresAt) {
            throw new BusinessException(GlobalErrorCode.INVALID_TOKEN, "만료된 access token입니다.");
        }
        return new MemberClaims(
                readLongClaim(claims, "authAccountId"),
                readLongClaim(claims, "memberId"),
                readStringClaim(claims, "role")
        );
    }

    private Map<String, String> parseAndVerify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("JWT 형식이 올바르지 않습니다.");
        }
        String unsigned = parts[0] + "." + parts[1];
        String expected = sign(unsigned);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("JWT 서명이 올바르지 않습니다.");
        }
        return decodeClaims(parts[1]);
    }

    private Map<String, String> decodeClaims(String encodedPayload) {
        String json = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        return parseFlatJson(json);
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            throw new IllegalStateException("JWT 서명 검증에 실패했습니다.", e);
        }
    }

    private Map<String, String> parseFlatJson(String json) {
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException("JWT payload JSON 형식이 올바르지 않습니다.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        String body = json.substring(1, json.length() - 1);
        for (String entry : body.split(",")) {
            String[] kv = entry.split(":", 2);
            if (kv.length != 2) {
                throw new IllegalArgumentException("JWT payload entry 형식이 올바르지 않습니다.");
            }
            values.put(trimQuotes(kv[0]), trimQuotes(kv[1]));
        }
        return values;
    }

    private long readLongClaim(Map<String, String> claims, String key) {
        String value = claims.get(key);
        if (value == null) throw new IllegalArgumentException("JWT claim을 읽을 수 없습니다: " + key);
        return Long.parseLong(value);
    }

    private String readStringClaim(Map<String, String> claims, String key) {
        String value = claims.get(key);
        if (value == null) throw new IllegalArgumentException("JWT claim을 읽을 수 없습니다: " + key);
        return value;
    }

    private String trimQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
