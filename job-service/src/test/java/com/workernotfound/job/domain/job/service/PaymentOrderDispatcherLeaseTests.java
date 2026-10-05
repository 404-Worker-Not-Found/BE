package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.port.PaymentOrderCreator;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실행권은 실제로 강제되는 전체 호출 제한시간에 취소·기록 여유(1초)를 더한 값 이상이어야 한다.
class PaymentOrderDispatcherLeaseTests {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(8);

    @Test
    void rejectsLeaseShorterThanOrEqualToCallTimeout() {
        assertRejected(CALL_TIMEOUT.minusMillis(1));
        assertRejected(CALL_TIMEOUT);
    }

    @Test
    void rejectsLeaseWithoutCancellationAndRecordingMargin() {
        assertRejected(CALL_TIMEOUT.plus(PaymentOrderDispatcher.LEASE_MARGIN).minusMillis(1));
    }

    @Test
    void acceptsLeaseCoveringCallTimeoutAndMargin() {
        assertThatCode(() -> dispatcher(CALL_TIMEOUT.plus(PaymentOrderDispatcher.LEASE_MARGIN)))
                .doesNotThrowAnyException();
    }

    private void assertRejected(Duration lease) {
        assertThatThrownBy(() -> dispatcher(lease))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration")
                .hasMessageContaining("call-timeout");
    }

    private PaymentOrderDispatcher dispatcher(Duration lease) {
        PaymentOrderDispatchProperties properties = new PaymentOrderDispatchProperties(
                false, Duration.ofSeconds(5), 50, lease, Duration.ofSeconds(2), Duration.ofMinutes(5));
        return new PaymentOrderDispatcher(null, null, new FixedCallDurationCreator(), properties, Clock.systemUTC());
    }

    private static final class FixedCallDurationCreator implements PaymentOrderCreator {

        @Override
        public CreatedPaymentOrder createOrder(PaymentOrderRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration maxCallDuration() {
            return CALL_TIMEOUT;
        }
    }
}
