package com.workernotfound.chat.global.security;

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
public class JwtTokenParser {

  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final JwtProperties jwtProperties;
  private byte[] secretKey;

  @PostConstruct
  void initialize() {
    String secret = jwtProperties.secret();
    if (secret == null || secret.isBlank())
      throw new IllegalStateException("JWT secret이 설정되지 않았습니다.");
    byte[] key = secret.getBytes(StandardCharsets.UTF_8);
    if (key.length < 32)
      throw new IllegalStateException("JWT secret은 32바이트 이상이어야 합니다.");
    this.secretKey = key;
  }

  public AuthenticatedMember parseAccessToken(String token) {
    return parseSession(token).member();
  }

  public AuthenticatedChatSession parseSession(String token) {
    if (token == null || token.isBlank())
      throw new InvalidAccessTokenException("access token이 없습니다.");
    try {
      Map<String, String> claims = parseAndVerify(token);
      long expiresAt = readLongClaim(claims, "exp");
      if (Instant.now().getEpochSecond() >= expiresAt) {
        throw new InvalidAccessTokenException("만료된 access token입니다.");
      }
      var member = new AuthenticatedMember(readLongClaim(claims, "authAccountId"),
          readLongClaim(claims, "memberId"), readRoleClaim(claims));
      return new AuthenticatedChatSession(member, expiresAt);
    } catch (IllegalArgumentException exception) {
      throw new InvalidAccessTokenException("JWT claim 형식이 올바르지 않습니다.", exception);
    }
  }

  private Map<String, String> parseAndVerify(String token) {
    String[] tokenParts = token.split("\\.");
    if (tokenParts.length != 3) {
      throw new InvalidAccessTokenException("JWT 형식이 올바르지 않습니다.");
    }
    String unsignedToken = "%s.%s".formatted(tokenParts[0], tokenParts[1]);
    String expectedSignature = sign(unsignedToken);
    if (!MessageDigest.isEqual(
        expectedSignature.getBytes(StandardCharsets.UTF_8),
        tokenParts[2].getBytes(StandardCharsets.UTF_8))) {
      throw new InvalidAccessTokenException("JWT 서명이 올바르지 않습니다.");
    }
    return decodeClaims(tokenParts[1]);
  }

  private Map<String, String> decodeClaims(String encodedPayload) {
    try {
      String json =
          new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
      return parseFlatJson(json);
    } catch (IllegalArgumentException exception) {
      throw new InvalidAccessTokenException("JWT payload를 읽을 수 없습니다.", exception);
    }
  }

  private String sign(String value) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
      byte[] signature = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
      throw new JwtProcessingException(exception);
    }
  }

  private Map<String, String> parseFlatJson(String json) {
    if (!json.startsWith("{") || !json.endsWith("}")) {
      throw new InvalidAccessTokenException("JWT payload JSON 형식이 올바르지 않습니다.");
    }
    Map<String, String> values = new LinkedHashMap<>();
    String body = json.substring(1, json.length() - 1);
    for (String entry : body.split(",")) {
      String[] keyValue = entry.split(":", 2);
      if (keyValue.length != 2) {
        throw new InvalidAccessTokenException("JWT payload entry 형식이 올바르지 않습니다.");
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
    throw new InvalidAccessTokenException("JWT claim을 읽을 수 없습니다: " + claimName);
  }

  private String readStringClaim(Map<String, String> claims, String claimName) {
    String value = claims.get(claimName);
    if (value != null) {
      return value;
    }
    throw new InvalidAccessTokenException("JWT claim을 읽을 수 없습니다: " + claimName);
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
