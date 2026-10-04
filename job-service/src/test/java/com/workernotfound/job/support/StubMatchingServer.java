package com.workernotfound.job.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 실제 HTTP로 응답하는 matching-service 대역.
 *
 * <p>타임아웃·연결 오류·헤더를 실제 소켓으로 검증하기 위해 JDK HTTP 서버를 사용한다. 응답 본문은 matching-service의
 * 공통 {@code ApiResponse} 형식을 따른다. 상대 서비스의 수신 처리 자체는 검증하지 않는다.
 */
public class StubMatchingServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Queue<StubResponse> responses = new ConcurrentLinkedQueue<>();
    private volatile StubResponse defaultResponse = StubResponse.success();
    private volatile Consumer<RecordedRequest> onRequest = request -> {
    };

    private StubMatchingServer(HttpServer server) {
        this.server = server;
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public static StubMatchingServer start() {
        try {
            return new StubMatchingServer(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void enqueue(StubResponse... stubResponses) {
        responses.addAll(List.of(stubResponses));
    }

    public void respondByDefault(StubResponse response) {
        this.defaultResponse = response;
    }

    // 응답을 보내기 전에 요청 스레드에서 실행된다. HTTP 처리 중의 DB 상태 관찰에 사용한다.
    public void onRequest(Consumer<RecordedRequest> hook) {
        this.onRequest = hook;
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        requests.clear();
        responses.clear();
        defaultResponse = StubResponse.success();
        onRequest = request -> {
        };
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            int bodyLength = exchange.getRequestBody().readAllBytes().length;
            RecordedRequest request = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    Map.of(
                            "X-Internal-Secret", headerOrEmpty(exchange, "X-Internal-Secret"),
                            "X-Job-Version", headerOrEmpty(exchange, "X-Job-Version"),
                            "Idempotency-Key", headerOrEmpty(exchange, "Idempotency-Key")
                    ),
                    bodyLength
            );
            requests.add(request);
            onRequest.accept(request);
            StubResponse response = responses.poll();
            respond(exchange, response == null ? defaultResponse : response);
        }
    }

    private void respond(HttpExchange exchange, StubResponse response) throws IOException {
        sleep(response.delay());
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private static String headerOrEmpty(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "" : value;
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    public record RecordedRequest(String method, String path, Map<String, String> headers, int bodyLength) {

        public String header(String name) {
            return headers.get(name);
        }
    }

    public record StubResponse(int status, String body, Duration delay) {

        public static StubResponse success() {
            return new StubResponse(200, """
                    {"success":true,"status":200,"code":"SUCCESS","message":"요청이 성공적으로 처리되었습니다.",\
                    "data":null,"path":null,"timestamp":"2026-10-04T10:00:00","reasons":null}""", Duration.ZERO);
        }

        // matching-service가 확정 Saga 미완료로 모집 완료 반영을 거절할 때의 응답
        public static StubResponse sagaInProgress() {
            return error(409, "APPLICATION-409-006", "매칭 확정 처리 중인 지원이 있어 모집 완료를 반영할 수 없습니다.");
        }

        public static StubResponse error(int status, String code, String message) {
            return new StubResponse(status, """
                    {"success":false,"status":%d,"code":"%s","message":"%s","data":null,\
                    "path":"/api/applications/internal/jobs/1/recruitment-completion",\
                    "timestamp":"2026-10-04T10:00:00","reasons":null}""".formatted(status, code, message), Duration.ZERO);
        }

        public StubResponse delayedBy(Duration duration) {
            return new StubResponse(status, body, duration);
        }
    }
}
