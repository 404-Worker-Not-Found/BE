package com.workernotfound.job.external.client.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionFailureType;
import com.workernotfound.job.domain.job.exception.RecruitmentCompletionNotificationException;
import com.workernotfound.job.global.security.InternalApiProperties;
import com.workernotfound.job.support.StubMatchingServer;
import com.workernotfound.job.support.StubMatchingServer.StubResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 송신 측 계약 검증이다. 실제 HTTP 대역 서버로 경로·헤더·타임아웃·응답 분류를 확인하며 matching-service 수신 처리는 검증하지 않는다.
 */
class MatchingRecruitmentCompletionClientTests {

    private static final String SECRET = "client-test-secret";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    private final List<MatchingRecruitmentCompletionClient> clients = new ArrayList<>();
    private StubMatchingServer server;

    @BeforeEach
    void setUp() {
        server = StubMatchingServer.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
        clients.forEach(MatchingRecruitmentCompletionClient::destroy);
    }

    @Test
    void sendsCompletionToContractPathWithThreeHeadersAndNoBody() {
        client(server.baseUrl()).notifyRecruitmentCompleted(10L, 3L, "0f8fad5b-d9cb-469f-a165-70867728950e");

        assertThat(server.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/api/applications/internal/jobs/10/recruitment-completion");
            assertThat(request.header("X-Internal-Secret")).isEqualTo(SECRET);
            assertThat(request.header("X-Job-Version")).isEqualTo("3");
            assertThat(request.header("Idempotency-Key")).isEqualTo("0f8fad5b-d9cb-469f-a165-70867728950e");
            assertThat(request.bodyLength()).isZero();
        });
    }

    @Test
    void classifiesSagaInProgressConflictAsTransient() {
        server.enqueue(StubResponse.sagaInProgress());

        assertFailure(() -> notifyCompletion(), RecruitmentCompletionFailureType.CONFLICT, 409, "APPLICATION-409-006");
        assertThat(RecruitmentCompletionFailureType.CONFLICT.isTransient()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "403, GLOBAL-403-001, AUTHENTICATION",
            "400, GLOBAL-400-001, CONTRACT",
            "404, GLOBAL-404-001, CONTRACT",
            "408, GLOBAL-408-001, TIMEOUT",
            "429, GLOBAL-429-001, THROTTLED",
            "500, GLOBAL-500-001, SERVER_ERROR",
            "503, GLOBAL-503-001, SERVER_ERROR"
    })
    void classifiesErrorStatuses(int status, String code, RecruitmentCompletionFailureType expected) {
        server.enqueue(StubResponse.error(status, code, "오류"));

        assertFailure(() -> notifyCompletion(), expected, status, code);
    }

    @Test
    void classifiesUnauthorizedAsAuthenticationFailure() {
        server.enqueue(StubResponse.error(401, "GLOBAL-401-001", "유효하지 않은 내부 API secret입니다."));

        // JDK HttpURLConnection은 401 응답 본문을 넘겨주지 않을 수 있으므로 오류 코드는 확인하지 않는다.
        assertThatThrownBy(this::notifyCompletion)
                .isInstanceOfSatisfying(RecruitmentCompletionNotificationException.class, exception -> {
                    assertThat(exception.getFailureType()).isEqualTo(RecruitmentCompletionFailureType.AUTHENTICATION);
                    assertThat(exception.getHttpStatus()).isEqualTo(401);
                });
    }

    @Test
    void authenticationAndContractFailuresAreNotTransient() {
        assertThat(RecruitmentCompletionFailureType.AUTHENTICATION.isTransient()).isFalse();
        assertThat(RecruitmentCompletionFailureType.CONTRACT.isTransient()).isFalse();
    }

    @Test
    void treatsSuccessStatusWithoutSuccessEnvelopeAsContractFailure() {
        server.enqueue(
                new StubResponse(200, "{\"success\":false,\"code\":\"SUCCESS\"}", Duration.ZERO),
                new StubResponse(200, "not-json", Duration.ZERO),
                new StubResponse(200, "", Duration.ZERO)
        );

        assertFailure(() -> notifyCompletion(), RecruitmentCompletionFailureType.CONTRACT, 200, "SUCCESS");
        assertFailure(() -> notifyCompletion(), RecruitmentCompletionFailureType.CONTRACT, 200, null);
        assertFailure(() -> notifyCompletion(), RecruitmentCompletionFailureType.CONTRACT, 200, null);
    }

    @Test
    void dropsResponseCodeThatIsNotASafeErrorCode() {
        server.enqueue(StubResponse.error(500, "secret=abc; <script>", "오류"));

        assertFailure(() -> notifyCompletion(), RecruitmentCompletionFailureType.SERVER_ERROR, 500, null);
    }

    @Test
    void classifiesResponseHeaderTimeout() {
        server.enqueue(StubResponse.success().delayedBy(READ_TIMEOUT.multipliedBy(5)));

        assertFailure(() -> notifyCompletion(), RecruitmentCompletionFailureType.TIMEOUT, null, null);
    }

    @Test
    void classifiesRefusedConnectionAsNetworkFailure() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }

        assertFailure(
                () -> client("http://127.0.0.1:" + unusedPort).notifyRecruitmentCompleted(1L, 1L, "command"),
                RecruitmentCompletionFailureType.NETWORK,
                null,
                null
        );
    }

    @Test
    void failureMessageDoesNotContainSecretOrResponseBody() {
        server.enqueue(StubResponse.error(401, "GLOBAL-401-001", "유효하지 않은 내부 API secret입니다."));

        assertThatThrownBy(this::notifyCompletion)
                .hasMessageNotContaining(SECRET)
                .hasMessageNotContaining("유효하지 않은");
    }

    @Test
    void rejectsNonLoopbackPlainHttpBaseUrl() {
        assertThatThrownBy(() -> client("http://matching.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> client("https://matching.example.com")).doesNotThrowAnyException();
    }

    private void notifyCompletion() {
        client(server.baseUrl()).notifyRecruitmentCompleted(1L, 2L, "command-id");
    }

    private void assertFailure(
            ThrowingCallable callable,
            RecruitmentCompletionFailureType type,
            Integer status,
            String code
    ) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(RecruitmentCompletionNotificationException.class, exception -> {
                    assertThat(exception.getFailureType()).isEqualTo(type);
                    assertThat(exception.getHttpStatus()).isEqualTo(status);
                    assertThat(exception.getResponseCode()).isEqualTo(code);
                });
    }

    private MatchingRecruitmentCompletionClient client(String baseUrl) {
        MatchingServiceProperties properties = new MatchingServiceProperties(
                baseUrl, Duration.ofSeconds(1), READ_TIMEOUT, Duration.ofSeconds(1));
        MatchingRecruitmentCompletionClient client = new MatchingRecruitmentCompletionClient(
                properties, new InternalApiProperties(SECRET), new ObjectMapper());
        clients.add(client);
        return client;
    }
}
