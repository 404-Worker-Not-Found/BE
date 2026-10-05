package com.workernotfound.job.domain.job.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 주문 생성 명령 전송 설정.
 *
 * <p>스케줄러 사용 여부는 {@code job.payment-order.dispatch-enabled}로 따로 켜고 끈다.
 */
@ConfigurationProperties(prefix = "job.payment-order")
public record PaymentOrderDispatchProperties(
        boolean dispatchAfterCommit,
        Duration dispatchInterval,
        int batchSize,
        Duration leaseDuration,
        Duration retryBaseDelay,
        Duration retryMaxDelay
) {

    public PaymentOrderDispatchProperties {
        requirePositive(dispatchInterval, "dispatch-interval");
        requirePositive(leaseDuration, "lease-duration");
        requirePositive(retryBaseDelay, "retry-base-delay");
        requirePositive(retryMaxDelay, "retry-max-delay");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("job.payment-order.batch-size는 0보다 커야 합니다.");
        }
        if (retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException("job.payment-order.retry-max-delay는 retry-base-delay 이상이어야 합니다.");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("job.payment-order.%s는 0보다 커야 합니다.".formatted(name));
        }
    }
}
