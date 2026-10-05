package com.workernotfound.job.external.client.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderFailureType;
import com.workernotfound.job.domain.job.exception.PaymentOrderCreationException;
import com.workernotfound.job.domain.job.port.PaymentOrderCreator;
import com.workernotfound.job.external.client.BoundedBodySubscriber;
import com.workernotfound.job.external.client.BoundedBodySubscriber.ReceivedBody;
import com.workernotfound.job.global.security.InternalApiProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

/**
 * payment-service의 결제 주문 생성 계약 {@code POST /api/payments/internal/orders}를 호출한다.
 *
 * <p>헤더는 {@code X-Internal-Secret}, {@code Idempotency-Key}, 본문은 저장된 명령 스냅샷
 * ({@code jobPostId}, {@code jobVersion}, {@code ownerMemberId}, {@code amount}, {@code currency})이다.
 * HTTP 성공 코드만으로 성공 처리하지 않는다. 공통 응답의 {@code success=true}, 필수 응답 필드, 요청 스냅샷과 같은 공고 ID·버전·금액·통화,
 * 연결할 수 있는 주문 상태를 모두 확인한 경우에만 주문을 돌려준다. 응답 원문과 요청 헤더는 보관하거나 로그로 남기지 않는다.
 *
 * <p>한 번의 호출은 연결 시작부터 본문 수신 완료까지 {@code call-timeout}을 넘지 않는다. 제한시간이 지나면 JDK {@link HttpClient}
 * 교환을 취소해 연결을 닫는다(모집 완료 알림 클라이언트와 같은 방식).
 */
@Component
@EnableConfigurationProperties(PaymentServiceProperties.class)
public class PaymentOrderClient implements PaymentOrderCreator, DisposableBean {

    static final String PATH = "/api/payments/internal/orders";
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final int MAX_CAUSE_DEPTH = 16;
    private static final int HTTP_WORKER_THREADS = 2;
    // 상대 응답의 오류 코드는 형식이 맞을 때만 보존한다. 임의 문자열이 저장소나 로그에 들어가지 않게 한다.
    private static final Pattern SAFE_RESPONSE_CODE = Pattern.compile("[A-Z0-9_-]{1,50}");
    // payment-service 주문 ID는 UUID이며 저장 컬럼은 64자다.
    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    // 생성된 주문이 결제 진행에 따라 가질 수 있는 상태. 멱등 재요청은 이미 진행된 상태를 돌려줄 수 있다.
    private static final Set<String> LINKABLE_ORDER_STATUSES =
            Set.of("READY", "CONFIRMING", "DEPOSITED", "FAILED", "REVIEW_REQUIRED");
    // 다른 주문으로 대체된 주문. 공고에 연결할 최신 주문이 아니다.
    private static final String SUPERSEDED_ORDER_STATUS = "SUPERSEDED";

    private final URI endpoint;
    private final String internalSecret;
    private final Duration readTimeout;
    private final Duration callTimeout;
    private final ObjectMapper objectMapper;
    private final ObjectReader strictJsonReader;
    private final ThreadPoolExecutor httpExecutor;
    private final HttpClient httpClient;

    public PaymentOrderClient(
            PaymentServiceProperties properties,
            InternalApiProperties internalApiProperties,
            ObjectMapper objectMapper
    ) {
        this.endpoint = URI.create(properties.baseUrl()).resolve(PATH);
        this.internalSecret = internalApiProperties.secret();
        this.readTimeout = properties.readTimeout();
        this.callTimeout = properties.callTimeout();
        this.objectMapper = objectMapper;
        // 금액은 소수 표기(예: 100000.00)로 온다. double로 읽으면 큰 금액의 정밀도가 사라지므로 처음부터 BigDecimal로 읽는다.
        this.strictJsonReader = objectMapper.reader().with(
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        // HTTP 클라이언트의 비동기 작업이 쓰는 스레드 수를 고정해 반복 호출에도 스레드가 늘지 않게 한다.
        this.httpExecutor = new ThreadPoolExecutor(
                HTTP_WORKER_THREADS, HTTP_WORKER_THREADS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), daemonThreadFactory());
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(httpExecutor)
                .build();
    }

    @Override
    public CreatedPaymentOrder createOrder(PaymentOrderRequest request) {
        // 응답 헤더를 받으면 상태를 기록해 둔다. 본문 수신이 실패해도 이미 받은 상태를 진단 정보로 남긴다.
        AtomicReference<Integer> receivedStatus = new AtomicReference<>();
        CompletableFuture<HttpResponse<ReceivedBody>> exchange = httpClient.sendAsync(
                httpRequest(request),
                responseInfo -> {
                    receivedStatus.set(responseInfo.statusCode());
                    return new BoundedBodySubscriber(MAX_BODY_BYTES);
                });
        HttpResponse<ReceivedBody> response = await(exchange, receivedStatus);
        return verify(request, response.statusCode(), parseReceivedBody(response.body()));
    }

    // 실제로 강제되는 전체 호출 제한시간
    @Override
    public Duration maxCallDuration() {
        return callTimeout;
    }

    @Override
    public void destroy() {
        httpExecutor.shutdownNow();
    }

    private HttpResponse<ReceivedBody> await(
            CompletableFuture<HttpResponse<ReceivedBody>> exchange,
            AtomicReference<Integer> receivedStatus
    ) {
        try {
            return exchange.get(callTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            // 기다림만 멈추지 않고 진행 중인 교환을 취소한다. JDK 16 이상에서 HTTP/1.1 연결이 닫힌다.
            exchange.cancel(true);
            throw new PaymentOrderCreationException(PaymentOrderFailureType.TIMEOUT, receivedStatus.get(), exception);
        } catch (ExecutionException exception) {
            throw transportFailure(exception.getCause(), receivedStatus.get());
        } catch (CancellationException exception) {
            throw new PaymentOrderCreationException(PaymentOrderFailureType.NETWORK, receivedStatus.get(), exception);
        } catch (InterruptedException exception) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new PaymentOrderCreationException(PaymentOrderFailureType.NETWORK, receivedStatus.get(), exception);
        }
    }

    // 연결·헤더·본문 수신 중 전송 오류. 본문을 받다 실패해도 상태가 아니라 전송 실패로 분류한다.
    private PaymentOrderCreationException transportFailure(Throwable cause, Integer receivedStatus) {
        if (!(cause instanceof IOException)) {
            // 전송 오류가 아닌 예외는 내부 오류다. 통신·계약 실패로 바꾸지 않고 드러내 실행기 경계에서 기록되게 한다.
            throw new IllegalStateException("결제 주문 생성 교환 중 예상하지 못한 오류가 발생했습니다.", cause);
        }
        PaymentOrderFailureType type = isTimeout(cause) ? PaymentOrderFailureType.TIMEOUT : PaymentOrderFailureType.NETWORK;
        return new PaymentOrderCreationException(type, receivedStatus, cause);
    }

    private HttpRequest httpRequest(PaymentOrderRequest request) {
        return HttpRequest.newBuilder(endpoint)
                // 응답 헤더까지의 제한. 본문 수신을 포함한 전체 제한은 await()에서 강제한다.
                .timeout(readTimeout)
                // TODO: API Gateway나 mTLS 기반 서비스 간 인증으로 대체한다.
                .header("X-Internal-Secret", internalSecret)
                .header("Idempotency-Key", request.idempotencyKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody(request)))
                .build();
    }

    private byte[] requestBody(PaymentOrderRequest request) {
        try {
            return objectMapper.writeValueAsBytes(new OrderBody(
                    request.jobPostId(),
                    request.jobVersion(),
                    request.ownerMemberId(),
                    request.amount(),
                    request.currency()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("결제 주문 생성 요청 본문을 만들 수 없습니다.", exception);
        }
    }

    // 본문을 끝까지 받은 응답만 여기에 온다.
    private CreatedPaymentOrder verify(PaymentOrderRequest request, int status, JsonNode body) {
        if (!isSuccessful(status)) {
            throw new PaymentOrderCreationException(failureTypeOf(status), status, safeResponseCode(body));
        }
        JsonNode data = successData(status, body);
        String orderId = requireText(status, data, "orderId");
        String orderStatus = requireText(status, data, "status");
        if (!ORDER_ID.matcher(orderId).matches()) {
            throw contractFailure(status);
        }
        verifySnapshot(request, status, data);
        if (SUPERSEDED_ORDER_STATUS.equals(orderStatus)) {
            throw new PaymentOrderCreationException(PaymentOrderFailureType.ORDER_SUPERSEDED, status, (String) null);
        }
        if (!LINKABLE_ORDER_STATUSES.contains(orderStatus)) {
            throw contractFailure(status);
        }
        return new CreatedPaymentOrder(orderId, orderStatus);
    }

    // 2xx이면서 success가 불리언 true이고 data가 객체인 공통 응답만 통과한다.
    private JsonNode successData(int status, JsonNode body) {
        if (body == null || !body.path("success").isBoolean() || !body.path("success").booleanValue()) {
            throw contractFailure(status);
        }
        JsonNode data = body.path("data");
        if (!data.isObject()) {
            throw contractFailure(status);
        }
        return data;
    }

    private void verifySnapshot(PaymentOrderRequest request, int status, JsonNode data) {
        long jobPostId = requireLong(status, data, "jobPostId");
        long jobVersion = requireLong(status, data, "jobVersion");
        BigDecimal amount = requireDecimal(status, data, "amount");
        String currency = requireText(status, data, "currency");
        boolean isSameSnapshot = jobPostId == request.jobPostId()
                && jobVersion == request.jobVersion()
                && amount.compareTo(BigDecimal.valueOf(request.amount())) == 0
                && currency.equals(request.currency());
        if (!isSameSnapshot) {
            throw new PaymentOrderCreationException(PaymentOrderFailureType.SNAPSHOT_MISMATCH, status, (String) null);
        }
    }

    private String requireText(int status, JsonNode data, String field) {
        JsonNode value = data.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw contractFailure(status);
        }
        return value.textValue();
    }

    private long requireLong(int status, JsonNode data, String field) {
        JsonNode value = data.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw contractFailure(status);
        }
        return value.longValue();
    }

    private BigDecimal requireDecimal(int status, JsonNode data, String field) {
        JsonNode value = data.path(field);
        if (!value.isNumber()) {
            throw contractFailure(status);
        }
        return value.decimalValue();
    }

    private PaymentOrderCreationException contractFailure(int status) {
        return new PaymentOrderCreationException(PaymentOrderFailureType.CONTRACT, status, (String) null);
    }

    private PaymentOrderFailureType failureTypeOf(int status) {
        if (status == HttpStatus.CONFLICT.value()) {
            return PaymentOrderFailureType.CONFLICT;
        }
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
            return PaymentOrderFailureType.AUTHENTICATION;
        }
        if (status == HttpStatus.REQUEST_TIMEOUT.value()) {
            return PaymentOrderFailureType.TIMEOUT;
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return PaymentOrderFailureType.THROTTLED;
        }
        if (status >= 500 && status < 600) {
            return PaymentOrderFailureType.SERVER_ERROR;
        }
        // 그 밖의 4xx와 2xx가 아닌 나머지 상태
        return PaymentOrderFailureType.CONTRACT;
    }

    /**
     * 정상 수신한 본문을 JSON으로 해석한다. 빈 본문, 한도를 넘어 잘린 본문, JSON 형식이 아니거나 뒤에 다른 내용이 붙은
     * 본문은 null이며, 이때는 HTTP 상태로만 분류하고 상대 오류 코드는 보존하지 않는다.
     */
    private JsonNode parseReceivedBody(ReceivedBody body) {
        if (body.isTruncated() || body.bytes().length == 0) {
            return null;
        }
        try {
            return strictJsonReader.readTree(body.bytes());
        } catch (JsonProcessingException exception) {
            return null;
        } catch (IOException exception) {
            // 메모리의 바이트 배열을 읽는 중에는 형식 오류 외의 I/O 오류가 생기지 않는다.
            throw new UncheckedIOException(exception);
        }
    }

    private String safeResponseCode(JsonNode body) {
        if (body == null) {
            return null;
        }
        String code = body.path("code").textValue();
        return code != null && SAFE_RESPONSE_CODE.matcher(code).matches() ? code : null;
    }

    private boolean isTimeout(Throwable exception) {
        Throwable cause = exception;
        // 순환 cause에서 멈추도록 탐색 깊이를 제한한다.
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean isSuccessful(int status) {
        return status >= 200 && status < 300;
    }

    private static CustomizableThreadFactory daemonThreadFactory() {
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("payment-http-");
        threadFactory.setDaemon(true);
        return threadFactory;
    }

    // payment-service CreateOrderRequest와 같은 필드 이름과 순서
    private record OrderBody(Long jobPostId, Long jobVersion, Long ownerMemberId, Long amount, String currency) {
    }
}
