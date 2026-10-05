package com.workernotfound.job.external.client.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderFailureType;
import com.workernotfound.job.domain.job.exception.PaymentOrderCreationException;
import com.workernotfound.job.domain.job.port.PaymentOrderCreator.CreatedPaymentOrder;
import com.workernotfound.job.domain.job.port.PaymentOrderCreator.PaymentOrderRequest;
import com.workernotfound.job.global.security.InternalApiProperties;
import com.workernotfound.job.support.StubPaymentServer;
import com.workernotfound.job.support.StubPaymentServer.StubResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 송신 측 계약 검증이다. 실제 HTTP 대역 서버로 경로·헤더·본문·타임아웃과 응답 검증·분류를 확인한다.
 * payment-service의 수신 처리 자체는 payment-service 테스트에서 검증한다.
 */
class PaymentOrderClientTests {

    private static final String SECRET = "client-test-secret";
    private static final String KEY = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String ORDER_ID = "6a1f4f39-5d4e-4e8b-9a8f-2b9c1f0e7d11";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final Duration CALL_TIMEOUT = Duration.ofMillis(800);
    private static final PaymentOrderRequest REQUEST = new PaymentOrderRequest(KEY, 10L, 1L, 20L, 185_760L, "KRW");

    private final List<PaymentOrderClient> clients = new ArrayList<>();
    private StubPaymentServer server;

    @BeforeEach
    void setUp() {
        server = StubPaymentServer.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
        clients.forEach(PaymentOrderClient::destroy);
    }

    @Test
    void sendsSnapshotToContractPathWithHeadersAndReturnsVerifiedOrder() {
        CreatedPaymentOrder order = client(server.baseUrl()).createOrder(REQUEST);

        assertThat(order.orderId()).isEqualTo(server.issuedOrderId(KEY));
        assertThat(order.orderStatus()).isEqualTo("READY");
        assertThat(server.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/api/payments/internal/orders");
            assertThat(request.header("X-Internal-Secret")).isEqualTo(SECRET);
            assertThat(request.header("Idempotency-Key")).isEqualTo(KEY);
            assertThat(request.header("Content-Type")).startsWith("application/json");
            JsonNode body = request.jsonBody();
            assertThat(body.size()).isEqualTo(5);
            assertThat(body.path("jobPostId").longValue()).isEqualTo(10L);
            assertThat(body.path("jobVersion").longValue()).isEqualTo(1L);
            assertThat(body.path("ownerMemberId").longValue()).isEqualTo(20L);
            assertThat(body.path("amount").isIntegralNumber()).isTrue();
            assertThat(body.path("amount").longValue()).isEqualTo(185_760L);
            assertThat(body.path("currency").textValue()).isEqualTo("KRW");
        });
    }

    // 멱등 재요청 시점에는 결제가 이미 진행됐을 수 있다. 주문이 존재하므로 같은 주문 ID를 연결한다.
    @ParameterizedTest
    @ValueSource(strings = {"READY", "CONFIRMING", "DEPOSITED", "FAILED", "REVIEW_REQUIRED"})
    void acceptsOrderStatusesReachableAfterCreation(String status) {
        server.enqueue(order(ORDER_ID, 10, 1, "185760.00", "KRW", status));

        assertThat(client(server.baseUrl()).createOrder(REQUEST))
                .isEqualTo(new CreatedPaymentOrder(ORDER_ID, status));
    }

    @Test
    void rejectsSupersededOrder() {
        server.enqueue(order(ORDER_ID, 10, 1, "185760.00", "KRW", "SUPERSEDED"));

        assertFailure(PaymentOrderFailureType.ORDER_SUPERSEDED, 200, null);
    }

    @ParameterizedTest
    @CsvSource({
            "11, 1, 185760.00, KRW",
            "10, 2, 185760.00, KRW",
            "10, 1, 185761.00, KRW",
            "10, 1, 185760.50, KRW",
            "10, 1, 185760.00, USD"
    })
    void rejectsResponseThatDoesNotMatchRequestSnapshot(long jobPostId, long jobVersion, String amount, String currency) {
        server.enqueue(order(ORDER_ID, jobPostId, jobVersion, amount, currency, "READY"));

        assertFailure(PaymentOrderFailureType.SNAPSHOT_MISMATCH, 200, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not-json",
            "{\"success\":true}",
            "{\"success\":true,\"data\":null}",
            "{\"success\":\"true\",\"data\":{}}",
            "{\"success\":false,\"code\":\"SUCCESS\",\"data\":{}}",
            "{\"success\":true,\"data\":{\"orderId\":\"o-1\"}} trailing",
            // 필수 필드 누락·형식 오류
            "{\"success\":true,\"data\":{\"jobPostId\":10,\"jobVersion\":1,\"amount\":185760,\"currency\":\"KRW\",\"status\":\"READY\"}}",
            "{\"success\":true,\"data\":{\"orderId\":\"o 1\",\"jobPostId\":10,\"jobVersion\":1,\"amount\":185760,\"currency\":\"KRW\",\"status\":\"READY\"}}",
            "{\"success\":true,\"data\":{\"orderId\":\"o-1\",\"jobPostId\":\"10\",\"jobVersion\":1,\"amount\":185760,\"currency\":\"KRW\",\"status\":\"READY\"}}",
            "{\"success\":true,\"data\":{\"orderId\":\"o-1\",\"jobPostId\":10,\"jobVersion\":1,\"amount\":\"185760\",\"currency\":\"KRW\",\"status\":\"READY\"}}",
            "{\"success\":true,\"data\":{\"orderId\":\"o-1\",\"jobPostId\":10,\"jobVersion\":1,\"amount\":185760,\"currency\":\"KRW\"}}",
            "{\"success\":true,\"data\":{\"orderId\":\"o-1\",\"jobPostId\":10,\"jobVersion\":1,\"amount\":185760,\"currency\":\"KRW\",\"status\":\"PAID\"}}"
    })
    void treatsMalformedSuccessResponseAsContractFailure(String body) {
        server.enqueue(new StubResponse(200, body));

        assertFailure(PaymentOrderFailureType.CONTRACT, 200, null);
    }

    @ParameterizedTest
    @CsvSource({
            "401, GLOBAL-401-001, AUTHENTICATION",
            "403, GLOBAL-403-001, AUTHENTICATION",
            "409, ORDER-409-001, CONFLICT",
            "400, GLOBAL-400-001, CONTRACT",
            "404, GLOBAL-404-001, CONTRACT",
            "408, GLOBAL-408-001, TIMEOUT",
            "429, GLOBAL-429-001, THROTTLED",
            "500, GLOBAL-500-001, SERVER_ERROR",
            "503, GLOBAL-503-001, SERVER_ERROR"
    })
    void classifiesErrorStatuses(int status, String code, PaymentOrderFailureType expected) {
        server.enqueue(StubResponse.error(status, code));

        assertFailure(expected, status, code);
    }

    @Test
    void keepsStatusClassificationButDropsCodeForMalformedErrorBody() {
        server.enqueue(new StubResponse(503, "<html>unavailable</html>"));

        assertFailure(PaymentOrderFailureType.SERVER_ERROR, 503, null);
    }

    @Test
    void timesOutWhenHeadersArriveAfterCallTimeout() {
        server.enqueue(order(ORDER_ID, 10, 1, "185760.00", "KRW", "READY").delayedBy(CALL_TIMEOUT.multipliedBy(2)));

        assertFailure(PaymentOrderFailureType.TIMEOUT, null, null);
    }

    @Test
    void timesOutWhileReceivingBodyAndKeepsReceivedStatusOnlyAsDiagnostics() {
        server.enqueue(StubResponse.partialSuccessBodyThenStall());
        long startedAt = System.nanoTime();

        assertFailure(PaymentOrderFailureType.TIMEOUT, 200, null);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(CALL_TIMEOUT.plusSeconds(2));
    }

    @Test
    void classifiesConnectionFailureAsNetwork() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        PaymentOrderClient client = client("http://127.0.0.1:" + unusedPort);

        assertThatThrownBy(() -> client.createOrder(REQUEST))
                .isInstanceOfSatisfying(PaymentOrderCreationException.class, failure ->
                        assertThat(failure.getFailureType()).isEqualTo(PaymentOrderFailureType.NETWORK));
    }

    @Test
    void failureMessagesDoNotContainSecretOrResponseBody() {
        server.enqueue(new StubResponse(500, "{\"success\":false,\"code\":\"GLOBAL-500-001\",\"message\":\"db password=leak\"}"));

        assertThatThrownBy(() -> client(server.baseUrl()).createOrder(REQUEST))
                .isInstanceOf(PaymentOrderCreationException.class)
                .hasMessageNotContaining(SECRET)
                .hasMessageNotContaining("leak")
                .hasMessageNotContaining(KEY);
    }

    @Test
    void reportsEnforcedCallTimeoutAsMaxCallDuration() {
        assertThat(client(server.baseUrl()).maxCallDuration()).isEqualTo(CALL_TIMEOUT);
    }

    private void assertFailure(PaymentOrderFailureType type, Integer status, String code) {
        ThrowingCallable call = () -> client(server.baseUrl()).createOrder(REQUEST);
        assertThatThrownBy(call).isInstanceOfSatisfying(PaymentOrderCreationException.class, failure -> {
            assertThat(failure.getFailureType()).isEqualTo(type);
            assertThat(failure.getHttpStatus()).isEqualTo(status);
            assertThat(failure.getResponseCode()).isEqualTo(code);
        });
    }

    private StubResponse order(String orderId, long jobPostId, long jobVersion, String amount, String currency, String status) {
        return StubResponse.order(orderId, jobPostId, jobVersion, amount, currency, status);
    }

    private PaymentOrderClient client(String baseUrl) {
        PaymentOrderClient client = new PaymentOrderClient(
                new PaymentServiceProperties(baseUrl, Duration.ofMillis(300), READ_TIMEOUT, CALL_TIMEOUT),
                new InternalApiProperties(SECRET),
                new ObjectMapper());
        clients.add(client);
        return client;
    }
}
