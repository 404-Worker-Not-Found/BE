package com.workernotfound.job.external.client.matching;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionFailureType;
import com.workernotfound.job.domain.job.exception.RecruitmentCompletionNotificationException;
import com.workernotfound.job.global.security.InternalApiProperties;
import com.workernotfound.job.support.RawHttpStubServer;
import com.workernotfound.job.support.RawHttpStubServer.Behavior;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 본문 수신 실패(전송 오류)와 정상 수신한 본문의 형식 오류를 실제 소켓으로 구분해 검증한다.
 *
 * <p>본문 수신 실패는 이미 받은 HTTP 상태와 관계없이 TIMEOUT·NETWORK로 분류하고 그 상태를 진단 정보로 보존한다.
 * 끝까지 받은 본문은 JSON 형식과 관계없이 HTTP 상태로 분류하며, 형식이 맞지 않으면 상대 오류 코드는 버린다.
 */
class MatchingRecruitmentCompletionBodyFailureTests {

    private static final String SECRET = "body-failure-test-secret";
    private static final Duration CALL_TIMEOUT = Duration.ofMillis(600);

    private final List<MatchingRecruitmentCompletionClient> clients = new ArrayList<>();
    private RawHttpStubServer server;

    @BeforeEach
    void setUp() {
        server = RawHttpStubServer.start();
    }

    @AfterEach
    void tearDown() {
        clients.forEach(MatchingRecruitmentCompletionClient::destroy);
        server.close();
    }

    @Test
    void classifiesStalledBodyAfterSuccessHeadersAsTimeoutKeepingStatus() throws Exception {
        server.behave(Behavior.partialBodyThenStall());

        assertFailure(RecruitmentCompletionFailureType.TIMEOUT, 200, null);
        assertThat(server.awaitAllClosed(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void classifiesConnectionLostDuringBodyAsNetworkKeepingStatus() {
        // 본문 길이를 100바이트로 알리고 일부만 보낸 뒤 연결을 끊는다. 형식이 잘못된 완전한 응답과 다르다.
        server.behave(Behavior.partialBodyThenDisconnect());

        assertFailure(RecruitmentCompletionFailureType.NETWORK, 200, null);
    }

    @Test
    void succeedsOnlyWithCompleteSuccessEnvelope() {
        server.behave(Behavior.completeResponse(200, "{\"success\":true,\"status\":200,\"code\":\"SUCCESS\",\"data\":null}"));

        assertThatCode(this::notifyCompletion).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // 정상 수신한 2xx이지만 계약에 맞는 성공 본문이 아니다.
            "200 | not-json                                 | ",
            "200 | ''                                       | ",
            "200 | {\"success\":false,\"code\":\"SUCCESS\"} | SUCCESS",
            "200 | {\"success\":\"true\"}                   | ",
            "200 | {\"success\":true}garbage               | ",
            "200 | {\"success\":true}{\"success\":false}  | "
    })
    void classifiesCompletelyReceivedNonSuccessBodyAsContract(int status, String body, String expectedCode) {
        server.behave(Behavior.completeResponse(status, body));

        assertFailure(RecruitmentCompletionFailureType.CONTRACT, status, expectedCode);
    }

    @Test
    void rejectsBodyLargerThanLimitEvenIfItStartsWithSuccess() {
        String oversized = "{\"success\":true,\"padding\":\"" + "x".repeat(9_000) + "\"}";
        server.behave(Behavior.completeResponse(200, oversized));

        assertFailure(RecruitmentCompletionFailureType.CONTRACT, 200, null);
    }

    @ParameterizedTest
    @CsvSource({
            "503, SERVER_ERROR",
            "500, SERVER_ERROR",
            "409, CONFLICT",
            "401, AUTHENTICATION",
            "403, AUTHENTICATION",
            "408, TIMEOUT",
            "429, THROTTLED",
            "400, CONTRACT"
    })
    void keepsHttpStatusClassificationWhenErrorBodyIsNotJson(int status, RecruitmentCompletionFailureType expected) {
        server.behave(Behavior.completeResponse(status, "<html>upstream error</html>"));

        assertFailure(expected, status, null);
    }

    @ParameterizedTest
    @CsvSource({
            "409, APPLICATION-409-006, CONFLICT",
            "503, GLOBAL-503-001, SERVER_ERROR",
            "429, GLOBAL-429-001, THROTTLED"
    })
    void keepsSafeErrorCodeFromWellFormedErrorBody(int status, String code, RecruitmentCompletionFailureType expected) {
        server.behave(Behavior.completeResponse(status, "{\"success\":false,\"code\":\"" + code + "\"}"));

        assertFailure(expected, status, code);
    }

    @Test
    void failureMessageContainsNeitherSecretNorResponseBody() {
        server.behave(Behavior.completeResponse(503, "{\"success\":false,\"code\":\"bad code\",\"message\":\"raw-detail\"}"));

        assertThatThrownBy(this::notifyCompletion)
                .isInstanceOf(RecruitmentCompletionNotificationException.class)
                .hasMessageNotContaining(SECRET)
                .hasMessageNotContaining("raw-detail")
                .hasMessageNotContaining("bad code");
    }

    @Test
    void doesNotDisguiseUnexpectedInternalErrorAsCommunicationOrContractFailure() {
        // JSON 해석 중 형식 오류가 아닌 런타임 오류(프로그래밍 오류)는 통신·계약 실패로 바꾸지 않는다.
        ObjectMapper brokenMapper = new ObjectMapper().registerModule(new SimpleModule()
                .addDeserializer(JsonNode.class, new JsonDeserializer<>() {
                    @Override
                    public JsonNode deserialize(JsonParser parser, DeserializationContext context) {
                        throw new IllegalStateException("broken deserializer");
                    }
                }));
        server.behave(Behavior.completeResponse(200, "{\"success\":true}"));

        assertThatThrownBy(() -> client(brokenMapper).notifyRecruitmentCompleted(1L, 2L, "command-id"))
                .isNotInstanceOf(RecruitmentCompletionNotificationException.class)
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertFailure(RecruitmentCompletionFailureType type, Integer status, String code) {
        assertThatThrownBy(this::notifyCompletion)
                .isInstanceOfSatisfying(RecruitmentCompletionNotificationException.class, exception -> {
                    assertThat(exception.getFailureType()).isEqualTo(type);
                    assertThat(exception.getHttpStatus()).isEqualTo(status);
                    assertThat(exception.getResponseCode()).isEqualTo(code);
                });
    }

    private void notifyCompletion() {
        client(new ObjectMapper()).notifyRecruitmentCompleted(1L, 2L, "0f8fad5b-d9cb-469f-a165-70867728950e");
    }

    private MatchingRecruitmentCompletionClient client(ObjectMapper objectMapper) {
        MatchingRecruitmentCompletionClient client = new MatchingRecruitmentCompletionClient(
                new MatchingServiceProperties(server.baseUrl(), Duration.ofMillis(200), CALL_TIMEOUT, CALL_TIMEOUT),
                new InternalApiProperties(SECRET),
                objectMapper
        );
        clients.add(client);
        return client;
    }
}
