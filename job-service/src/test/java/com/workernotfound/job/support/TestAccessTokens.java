package com.workernotfound.job.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * auth-service가 발급하는 access token과 같은 형식(HS256)의 테스트 토큰을 만든다.
 *
 * <p>서명 키는 애플리케이션이 실제로 바인딩한 {@code auth.jwt.secret} 값을 받아 사용한다. 보안 설정을 끄지 않고 JWT 필터를 그대로 통과시킨다.
 */
public final class TestAccessTokens {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private TestAccessTokens() {
    }

    public static String owner(String secret, long memberId) {
        return issue(secret, memberId, "OWNER");
    }

    public static String worker(String secret, long memberId) {
        return issue(secret, memberId, "WORKER");
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String issue(String secret, long memberId, String role) {
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode("{\"authAccountId\":%d,\"memberId\":%d,\"role\":\"%s\",\"exp\":%d}"
                .formatted(memberId + 1_000, memberId, role, Instant.now().plusSeconds(600).getEpochSecond()));
        String unsigned = header + "." + payload;
        return unsigned + "." + sign(secret, unsigned);
    }

    private static String encode(String json) {
        return ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
