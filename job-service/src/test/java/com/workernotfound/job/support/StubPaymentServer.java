package com.workernotfound.job.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 실제 HTTP로 응답하는 payment-service 결제 주문 생성 대역.
 *
 * <p>기본 응답은 payment-service의 멱등 계약을 흉내 낸다. 같은 {@code Idempotency-Key}와 같은 본문이면 처음 만든 주문 ID를 다시
 * 돌려주고, 같은 키에 다른 본문이면 409({@code ORDER-409-001})다. 실제 PG나 payment-service DB는 쓰지 않는다.
 */
public class StubPaymentServer implements AutoCloseable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long STALL_CONTENT_LENGTH = 100_000;
    private static final Duration MAX_STALL = Duration.ofSeconds(10);

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Queue<Function<RecordedRequest, StubResponse>> responses = new ConcurrentLinkedQueue<>();
    private final Map<String, IssuedOrder> ordersByKey = new ConcurrentHashMap<>();
    private volatile String orderStatus = "READY";
    private volatile Consumer<RecordedRequest> onRequest = request -> {
    };

    private StubPaymentServer(HttpServer server) {
        this.server = server;
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public static StubPaymentServer start() {
        try {
            return new StubPaymentServer(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // 다음 요청들에 순서대로 고정 응답을 준다. 큐가 비면 멱등 주문 생성 응답으로 돌아간다.
    public void enqueue(StubResponse... stubResponses) {
        for (StubResponse response : stubResponses) {
            responses.add(request -> response);
        }
    }

    // 다음 요청을 정상 처리(주문 생성·기록)하되 응답만 늦게 보낸다. 상대는 처리했지만 응답이 유실된 경우를 재현한다.
    public void enqueueProcessedButDelayed(Duration delay) {
        responses.add(request -> idempotentOrder(request).delayedBy(delay));
    }

    public void enqueue(Function<RecordedRequest, StubResponse> responder) {
        responses.add(responder);
    }

    // 멱등 응답이 돌려줄 주문 상태. 재요청 시점에 결제가 이미 진행된 경우를 재현한다.
    public void orderStatus(String status) {
        this.orderStatus = status;
    }

    public void onRequest(Consumer<RecordedRequest> hook) {
        this.onRequest = hook;
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public String issuedOrderId(String idempotencyKey) {
        IssuedOrder order = ordersByKey.get(idempotencyKey);
        return order == null ? null : order.orderId();
    }

    public int issuedOrderCount() {
        return ordersByKey.size();
    }

    public void reset() {
        requests.clear();
        responses.clear();
        ordersByKey.clear();
        orderStatus = "READY";
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
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RecordedRequest request = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    Map.of(
                            "X-Internal-Secret", headerOrEmpty(exchange, "X-Internal-Secret"),
                            "Idempotency-Key", headerOrEmpty(exchange, "Idempotency-Key"),
                            "Content-Type", headerOrEmpty(exchange, "Content-Type")
                    ),
                    body
            );
            requests.add(request);
            onRequest.accept(request);
            Function<RecordedRequest, StubResponse> responder = responses.poll();
            respond(exchange, responder == null ? idempotentOrder(request) : responder.apply(request));
        }
    }

    // payment-service처럼 키별 요청 지문을 비교하고 처음 만든 주문을 다시 돌려준다.
    private StubResponse idempotentOrder(RecordedRequest request) {
        JsonNode body = request.jsonBody();
        String fingerprint = body.toString();
        IssuedOrder order = ordersByKey.computeIfAbsent(
                request.header("Idempotency-Key"),
                key -> new IssuedOrder(UUID.randomUUID().toString(), fingerprint));
        if (!order.fingerprint().equals(fingerprint)) {
            return StubResponse.error(409, "ORDER-409-001");
        }
        return StubResponse.order(
                order.orderId(),
                body.path("jobPostId").longValue(),
                body.path("jobVersion").longValue(),
                body.path("amount").longValue() + ".00",
                body.path("currency").textValue(),
                orderStatus);
    }

    private void respond(HttpExchange exchange, StubResponse response) throws IOException {
        sleep(response.delay());
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (response.isStallingAfterPartialBody()) {
            exchange.sendResponseHeaders(response.status(), STALL_CONTENT_LENGTH);
            OutputStream output = exchange.getResponseBody();
            output.write(body);
            output.flush();
            sleep(MAX_STALL);
            return;
        }
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

    private record IssuedOrder(String orderId, String fingerprint) {
    }

    public record RecordedRequest(String method, String path, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name);
        }

        public JsonNode jsonBody() {
            try {
                return OBJECT_MAPPER.readTree(body);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    public record StubResponse(int status, String body, Duration delay, boolean isStallingAfterPartialBody) {

        public StubResponse(int status, String body) {
            this(status, body, Duration.ZERO, false);
        }

        // payment-service OrderResponse를 담은 성공 응답. amount는 DECIMAL(19,2) 직렬화 형태(예: 100000.00)다.
        public static StubResponse order(
                String orderId, long jobPostId, long jobVersion, String amount, String currency, String status) {
            return new StubResponse(200, """
                    {"success":true,"status":200,"code":"SUCCESS","message":"요청이 성공적으로 처리되었습니다.",\
                    "data":{"orderId":"%s","jobPostId":%d,"jobVersion":%d,"amount":%s,"currency":"%s","status":"%s"},\
                    "path":null,"timestamp":"2026-10-05T10:00:00","reasons":null}"""
                    .formatted(orderId, jobPostId, jobVersion, amount, currency, status));
        }

        public static StubResponse error(int status, String code) {
            return new StubResponse(status, """
                    {"success":false,"status":%d,"code":"%s","message":"오류","data":null,\
                    "path":"/api/payments/internal/orders","timestamp":"2026-10-05T10:00:00","reasons":null}"""
                    .formatted(status, code));
        }

        // 200 헤더와 성공 본문의 앞부분만 보낸 뒤 멈춘다. 본문 수신 중 시간 초과를 재현한다.
        public static StubResponse partialSuccessBodyThenStall() {
            return new StubResponse(200, "{\"success\":tr", Duration.ZERO, true);
        }

        public StubResponse delayedBy(Duration duration) {
            return new StubResponse(status, body, duration, isStallingAfterPartialBody);
        }
    }
}
