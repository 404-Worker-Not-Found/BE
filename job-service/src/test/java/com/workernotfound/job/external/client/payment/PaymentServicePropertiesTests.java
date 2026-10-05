package com.workernotfound.job.external.client.payment;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServicePropertiesTests {

    private static final Duration CONNECT = Duration.ofSeconds(3);
    private static final Duration READ = Duration.ofSeconds(5);
    private static final Duration CALL = Duration.ofSeconds(8);

    @Test
    void acceptsDefaults() {
        assertThatCode(() -> new PaymentServiceProperties("http://localhost:8085", CONNECT, READ, CALL))
                .doesNotThrowAnyException();
        assertThatCode(() -> new PaymentServiceProperties("https://payment.internal", CONNECT, READ, CALL))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://payment.internal:8085", "ftp://localhost"})
    void rejectsNonLoopbackHttpOrMissingBaseUrl(String baseUrl) {
        assertThatThrownBy(() -> new PaymentServiceProperties(baseUrl, CONNECT, READ, CALL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedTimeouts() {
        assertRejected(null, READ, CALL);
        assertRejected(Duration.ZERO, READ, CALL);
        assertRejected(Duration.ofNanos(999_999), READ, CALL);
        assertRejected(CONNECT, READ, Duration.ofHours(1).plusMillis(1));
        // 연결·헤더 제한은 전체 제한을 넘을 수 없다.
        assertRejected(CONNECT, CALL.plusMillis(1), CALL);
        assertRejected(CALL.plusMillis(1), READ, CALL);
    }

    private void assertRejected(Duration connect, Duration read, Duration call) {
        assertThatThrownBy(() -> new PaymentServiceProperties("http://localhost:8085", connect, read, call))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("job.payment-service");
    }
}
