package com.workernotfound.job.external.client.payment;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * payment-service 호출 설정.
 *
 * <ul>
 *   <li>{@code connectTimeout}: TCP 연결 수립 제한</li>
 *   <li>{@code readTimeout}: 요청을 보낸 뒤 응답 헤더를 받을 때까지의 제한. 본문 수신 시간은 포함하지 않는다.</li>
 *   <li>{@code callTimeout}: 연결 시작부터 응답 헤더와 본문 수신 완료까지 한 번의 호출 전체 제한. 초과하면 진행 중인
 *   교환을 취소해 연결을 닫는다.</li>
 * </ul>
 * 모든 값은 1밀리초 이상 1시간 이하이며, 연결·헤더 제한은 전체 제한을 넘을 수 없다.
 */
@ConfigurationProperties(prefix = "job.payment-service")
public record PaymentServiceProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration callTimeout
) {

    static final Duration MIN_TIMEOUT = Duration.ofMillis(1);
    static final Duration MAX_TIMEOUT = Duration.ofHours(1);

    public PaymentServiceProperties {
        validateBaseUrl(baseUrl);
        requireSupportedRange(connectTimeout, "connect-timeout");
        requireSupportedRange(readTimeout, "read-timeout");
        requireSupportedRange(callTimeout, "call-timeout");
        requireWithinCallTimeout(connectTimeout, callTimeout, "connect-timeout");
        requireWithinCallTimeout(readTimeout, callTimeout, "read-timeout");
    }

    // 밀리초 미만 값은 클라이언트에서 0(무제한 또는 즉시 만료)으로 해석될 수 있고, 지나치게 큰 값은 단위 변환에서 넘칠 수 있다.
    private static void requireSupportedRange(Duration value, String name) {
        if (value == null || value.compareTo(MIN_TIMEOUT) < 0 || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "job.payment-service.%s는 1ms 이상 1h 이하여야 합니다.".formatted(name));
        }
    }

    private static void requireWithinCallTimeout(Duration value, Duration callTimeout, String name) {
        if (value.compareTo(callTimeout) > 0) {
            throw new IllegalArgumentException(
                    "job.payment-service.%s는 call-timeout을 넘을 수 없습니다.".formatted(name));
        }
    }

    private static void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("job.payment-service.base-url은 필수입니다.");
        }
        URI uri = URI.create(baseUrl);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost())) {
            return;
        }
        throw new IllegalArgumentException("내부 서비스 URL은 HTTPS 또는 로컬 루프백 HTTP 주소여야 합니다.");
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host);
    }
}
