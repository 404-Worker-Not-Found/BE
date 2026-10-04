package com.workernotfound.job.external.client.matching;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "job.matching-service")
public record MatchingServiceProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {

    public MatchingServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("job.matching-service.base-url은 필수입니다.");
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(readTimeout, "read-timeout");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("job.matching-service.%s는 0보다 커야 합니다.".formatted(name));
        }
    }
}
