package com.workernotfound.job.global.security;

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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

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
            parseAccessToken(token);
            return true;
        } catch (InvalidAccessTokenException exception) {
            return false;
        }
    }

    public MemberClaims parseAccessToken(String token) {
        if (token == null || token.isBlank())
            throw new InvalidAccessTokenException("access token이 없습니다.");
        try {
            Map<String, String> claims = parseAndVerify(token);
            long expiresAt = readLongClaim(claims, "exp");
            if (Instant.now().getEpochSecond() >= expiresAt) {
                throw new InvalidAccessTokenException("만료된 access token입니다.");
            }
            return new MemberClaims(
                    readLongClaim(claims, "authAccountId"),
                    readLongClaim(claims, "memberId"),
                    readRoleClaim(claims));
        } catch (IllegalArgumentException exception) {
            throw new InvalidAccessTokenException("JWT claim 형식이 올바르지 않습니다.", exception);
        }
    }

    private Map<String, String> parseAndVerify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidAccessTokenException("JWT 형식이 올바르지 않습니다.");
        }
        String unsigned = parts[0] + "." + parts[1];
        String expected = sign(unsigned);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidAccessTokenException("JWT 서명이 올바르지 않습니다.");
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
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new JwtProcessingException(e);
        }
    }

    private Map<String, String> parseFlatJson(String json) {
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new InvalidAccessTokenException("JWT payload JSON 형식이 올바르지 않습니다.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        String body = json.substring(1, json.length() - 1);
        for (String entry : body.split(",")) {
            String[] kv = entry.split(":", 2);
            if (kv.length != 2) {
                throw new InvalidAccessTokenException("JWT payload entry 형식이 올바르지 않습니다.");
            }
            values.put(trimQuotes(kv[0]), trimQuotes(kv[1]));
        }
        return values;
    }

    private long readLongClaim(Map<String, String> claims, String key) {
        String value = claims.get(key);
        if (value == null) throw new InvalidAccessTokenException("JWT claim을 읽을 수 없습니다: " + key);
        return Long.parseLong(value);
    }

    private String readStringClaim(Map<String, String> claims, String key) {
        String value = claims.get(key);
        if (value == null) throw new InvalidAccessTokenException("JWT claim을 읽을 수 없습니다: " + key);
        return value;
    }

    private String readRoleClaim(Map<String, String> claims) {
        String role = readStringClaim(claims, "role");
        if (!role.equals("OWNER") && !role.equals("WORKER")) {
            throw new InvalidAccessTokenException("JWT role이 올바르지 않습니다.");
        }
        return role;
    }

    private String trimQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
