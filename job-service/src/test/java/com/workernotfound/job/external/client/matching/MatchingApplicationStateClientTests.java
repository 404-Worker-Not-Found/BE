package com.workernotfound.job.external.client.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;
import com.workernotfound.job.domain.job.exception.ApplicationStateSnapshotException;
import com.workernotfound.job.domain.job.port.ApplicationStateSnapshot;
import com.workernotfound.job.global.security.InternalApiProperties;
import com.workernotfound.job.support.StubMatchingServer;
import com.workernotfound.job.support.StubMatchingServer.StubResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 송신 측 계약 검증이다. 실제 HTTP 대역 서버로 경로·헤더·응답 해석을 확인하며 matching-service 수신 처리는 검증하지 않는다.
 */
class MatchingApplicationStateClientTests {

    private static final String SECRET = "client-test-secret";
    private static final long JOB_POST_ID = 10L;

    private StubMatchingServer server;
    private MatchingApplicationStateClient client;

    @BeforeEach
    void setUp() {
        server = StubMatchingServer.start();
        client = new MatchingApplicationStateClient(
                new MatchingServiceProperties(server.baseUrl(), Duration.ofMillis(300), Duration.ofMillis(300),
                        Duration.ofMillis(500)),
                new InternalApiProperties(SECRET),
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void readsEveryApplicationStateFromContractPath() {
        server.enqueue(success(JOB_POST_ID, """
                [{"applicationId":3,"status":"APPLIED","revision":1},
                 {"applicationId":4,"status":"CANCELED","revision":2}]"""));

        assertThat(client.readApplicationStates(JOB_POST_ID)).containsExactly(
                new ApplicationStateSnapshot(3L, ApplicationStatus.APPLIED, 1L),
                new ApplicationStateSnapshot(4L, ApplicationStatus.CANCELED, 2L));
        assertThat(server.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).isEqualTo("/api/applications/internal/jobs/10/application-states");
            assertThat(request.header("X-Internal-Secret")).isEqualTo(SECRET);
        });
    }

    @Test
    void readsEmptySnapshot() {
        server.enqueue(success(JOB_POST_ID, "[]"));

        assertThat(client.readApplicationStates(JOB_POST_ID)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"applicationId\":3,\"status\":\"UNKNOWN\",\"revision\":1}]",
            "[{\"applicationId\":3,\"status\":\"APPLIED\",\"revision\":0}]",
            "[{\"applicationId\":\"3\",\"status\":\"APPLIED\",\"revision\":1}]",
            "[{\"status\":\"APPLIED\",\"revision\":1}]",
            "[{\"applicationId\":3,\"status\":\"APPLIED\",\"revision\":1},{\"applicationId\":3,\"status\":\"APPLIED\",\"revision\":1}]",
            "null"
    })
    void rejectsRowsThatBreakContract(String applications) {
        server.enqueue(success(JOB_POST_ID, applications));

        assertThatThrownBy(() -> client.readApplicationStates(JOB_POST_ID))
                .isInstanceOf(ApplicationStateSnapshotException.class);
    }

    @Test
    void rejectsSnapshotOfAnotherJob() {
        server.enqueue(success(JOB_POST_ID + 1, "[]"));

        assertThatThrownBy(() -> client.readApplicationStates(JOB_POST_ID))
                .isInstanceOf(ApplicationStateSnapshotException.class);
    }

    @Test
    void rejectsFailureResponseAndNonJsonBody() {
        server.enqueue(StubResponse.error(401, "GLOBAL-401-001", "유효하지 않은 내부 API secret입니다."),
                new StubResponse(500, "<html>error</html>", Duration.ZERO));

        assertThatThrownBy(() -> client.readApplicationStates(JOB_POST_ID))
                .isInstanceOf(ApplicationStateSnapshotException.class);
        assertThatThrownBy(() -> client.readApplicationStates(JOB_POST_ID))
                .isInstanceOf(ApplicationStateSnapshotException.class);
    }

    @Test
    void failsWhenCallTimeoutPasses() {
        server.enqueue(success(JOB_POST_ID, "[]").delayedBy(Duration.ofSeconds(2)));

        assertThatThrownBy(() -> client.readApplicationStates(JOB_POST_ID))
                .isInstanceOf(ApplicationStateSnapshotException.class);
    }

    private static StubResponse success(long jobPostId, String applications) {
        return new StubResponse(200, """
                {"success":true,"status":200,"code":"SUCCESS","message":"요청이 성공적으로 처리되었습니다.",\
                "data":{"jobPostId":%d,"applications":%s},"path":null,"timestamp":"2026-11-03T10:00:00",\
                "reasons":null}""".formatted(jobPostId, applications), Duration.ZERO);
    }
}
