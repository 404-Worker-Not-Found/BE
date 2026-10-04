package com.workernotfound.job.external.client.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionFailureType;
import com.workernotfound.job.domain.job.exception.RecruitmentCompletionNotificationException;
import com.workernotfound.job.global.security.InternalApiProperties;
import com.workernotfound.job.support.RawHttpStubServer;
import com.workernotfound.job.support.RawHttpStubServer.Behavior;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 한 번의 호출 전체 제한시간을 실제 소켓으로 검증한다.
 *
 * <p>대역 서버는 헤더 전 정지, 헤더를 조금씩 보내기, 본문 일부 후 정지, 읽기 간격보다 짧은 간격으로 본문 보내기를 재현하고
 * 클라이언트가 연결을 닫은 시각을 기록한다. 이전 {@code HttpURLConnection} 구현은 본문을 조금씩 받는 동안 연결 100ms·읽기
 * 300ms 설정에서도 10초 넘게 끝나지 않았다.
 */
class MatchingRecruitmentCompletionCallTimeoutTests {

    private static final Duration CALL_TIMEOUT = Duration.ofMillis(600);
    private static final Duration DRIP_INTERVAL = Duration.ofMillis(50);
    // 스케줄링 지연을 고려한 여유. 이전 구현의 10초와 충분히 구분된다.
    private static final Duration TOLERANCE = Duration.ofMillis(700);

    private RawHttpStubServer server;
    private MatchingRecruitmentCompletionClient client;

    @BeforeEach
    void setUp() {
        server = RawHttpStubServer.start();
        client = new MatchingRecruitmentCompletionClient(
                new MatchingServiceProperties(server.baseUrl(), Duration.ofMillis(200), CALL_TIMEOUT, CALL_TIMEOUT),
                new InternalApiProperties("timeout-test-secret"),
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        client.destroy();
        server.close();
    }

    @Test
    void reportsEnforcedCallTimeoutAsMaxCallDuration() {
        assertThat(client.maxCallDuration()).isEqualTo(CALL_TIMEOUT);
    }

    @Test
    void succeedsWhenResponseArrivesWithinCallTimeout() {
        server.behave(Behavior.success());

        assertThatCode(this::notifyCompletion).doesNotThrowAnyException();
    }

    @Test
    void timesOutAndClosesConnectionWhenServerStallsBeforeHeaders() throws Exception {
        server.behave(Behavior.stallBeforeHeaders());

        assertTimedOutAndClosed();
    }

    @Test
    void timesOutAndClosesConnectionWhenHeadersArriveSlowly() throws Exception {
        server.behave(Behavior.dripHeaders(DRIP_INTERVAL));

        assertTimedOutAndClosed();
    }

    @Test
    void timesOutAndClosesConnectionWhenBodyStallsAfterSuccessHeaders() throws Exception {
        server.behave(Behavior.partialBodyThenStall());

        assertTimedOutAndClosed();
    }

    @Test
    void timesOutAndClosesConnectionWhenBodyKeepsArrivingSlowerThanCallTimeoutAllows() throws Exception {
        // 바이트 간격(50ms)이 헤더 제한(600ms)보다 훨씬 짧아 읽기 간격 제한만으로는 끝나지 않는 응답이다.
        server.behave(Behavior.dripBody(DRIP_INTERVAL));

        assertTimedOutAndClosed();
    }

    @Test
    void processesNextCommandAfterCallTimeout() throws Exception {
        server.behave(Behavior.dripBody(DRIP_INTERVAL));
        assertTimedOutAndClosed();

        server.behave(Behavior.success());

        assertThatCode(this::notifyCompletion).doesNotThrowAnyException();
    }

    @Test
    void repeatedTimeoutsDoNotAccumulateConnectionsTasksOrThreads() throws Exception {
        server.behave(Behavior.dripBody(DRIP_INTERVAL));
        assertTimedOutAndClosed();
        long httpThreadsBefore = countThreads("matching-http-");
        long selectorThreadsBefore = countThreads("SelectorManager");

        for (int i = 0; i < 5; i++) {
            assertTimedOutAndClosed();
        }

        assertThat(server.awaitAllClosed(Duration.ofSeconds(2))).isTrue();
        assertThat(server.clientClosedAtNanos()).hasSize(6);
        assertThat(client.httpExecutor().getActiveCount()).isZero();
        assertThat(client.httpExecutor().getQueue()).isEmpty();
        assertThat(countThreads("matching-http-")).isLessThanOrEqualTo(Math.max(httpThreadsBefore, 2));
        assertThat(countThreads("SelectorManager")).isEqualTo(selectorThreadsBefore);
    }

    private void assertTimedOutAndClosed() throws InterruptedException {
        int closedBefore = server.clientClosedAtNanos().size();
        long start = System.nanoTime();

        assertThatThrownBy(this::notifyCompletion)
                .isInstanceOfSatisfying(RecruitmentCompletionNotificationException.class, exception ->
                        assertThat(exception.getFailureType()).isEqualTo(RecruitmentCompletionFailureType.TIMEOUT));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isGreaterThanOrEqualTo(CALL_TIMEOUT.minusMillis(50));
        assertThat(elapsed).isLessThan(CALL_TIMEOUT.plus(TOLERANCE));
        // 호출자만 돌아온 것이 아니라 연결이 실제로 닫혀 대역 서버가 이를 관찰해야 한다.
        assertThat(server.awaitAllClosed(TOLERANCE)).isTrue();
        List<Long> closedAt = server.clientClosedAtNanos();
        assertThat(closedAt).hasSize(closedBefore + 1);
        assertThat(Duration.ofNanos(closedAt.get(closedAt.size() - 1) - start))
                .isLessThan(CALL_TIMEOUT.plus(TOLERANCE));
    }

    private void notifyCompletion() {
        client.notifyRecruitmentCompleted(1L, 2L, "0f8fad5b-d9cb-469f-a165-70867728950e");
    }

    private static long countThreads(String namePart) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().contains(namePart))
                .count();
    }
}
