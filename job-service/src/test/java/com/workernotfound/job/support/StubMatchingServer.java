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
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final long DRIP_CONTENT_LENGTH = 100_000;
    private static final Duration MAX_DRIP_DURATION = Duration.ofSeconds(10);

    private final Queue<StubResponse> responses = new ConcurrentLinkedQueue<>();
    private final AtomicInteger clientClosedDrips = new AtomicInteger();
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
        clientClosedDrips.set(0);
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
        if (response.dripInterval() != null) {
            dripBody(exchange, response);
            return;
        }
        if (response.isStallingAfterPartialBody()) {
            stallAfterPartialBody(exchange, response);
            return;
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    // 헤더를 보낸 뒤 본문을 한 바이트씩 계속 보낸다. 클라이언트가 연결을 닫으면 쓰기가 실패하며 끝난다.
    private void dripBody(HttpExchange exchange, StubResponse response) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), DRIP_CONTENT_LENGTH);
        OutputStream output = exchange.getResponseBody();
        long deadline = System.nanoTime() + MAX_DRIP_DURATION.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                output.write(' ');
                output.flush();
                sleep(response.dripInterval());
            }
        } catch (IOException exception) {
            clientClosedDrips.incrementAndGet();
        }
    }

    // 200 헤더와 선언 길이보다 짧은 본문 일부를 보낸 뒤 더 보내지 않는다. 서버 종료 시 인터럽트로 끝난다.
    private void stallAfterPartialBody(HttpExchange exchange, StubResponse response) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), DRIP_CONTENT_LENGTH);
        OutputStream output = exchange.getResponseBody();
        output.write(response.body().getBytes(StandardCharsets.UTF_8));
        output.flush();
        sleep(MAX_DRIP_DURATION);
    }

    // 본문을 보내는 도중 클라이언트가 연결을 닫은 횟수
    public int clientClosedDrips() {
        return clientClosedDrips.get();
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

    public record StubResponse(
            int status,
            String body,
            Duration delay,
            Duration dripInterval,
            boolean isStallingAfterPartialBody
    ) {

        public StubResponse(int status, String body, Duration delay) {
            this(status, body, delay, null, false);
        }

        // 200 헤더와 성공 본문의 앞부분만 보낸 뒤 멈춘다. 본문 수신 중 시간 초과를 재현한다.
        public static StubResponse partialSuccessBodyThenStall() {
            return new StubResponse(200, "{\"success\":tr", Duration.ZERO, null, true);
        }

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
            return new StubResponse(status, body, duration, dripInterval, isStallingAfterPartialBody);
        }

        // 200 헤더 뒤 본문을 간격마다 한 바이트씩 끝없이 보낸다. 읽기 간격 제한으로는 끝나지 않는 응답이다.
        public static StubResponse drippingBody(Duration interval) {
            return new StubResponse(200, "", Duration.ZERO, interval, false);
        }
    }
}
