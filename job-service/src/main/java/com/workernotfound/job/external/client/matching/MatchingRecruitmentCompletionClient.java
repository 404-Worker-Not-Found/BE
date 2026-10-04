package com.workernotfound.job.external.client.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionFailureType;
import com.workernotfound.job.domain.job.exception.RecruitmentCompletionNotificationException;
import com.workernotfound.job.domain.job.port.RecruitmentCompletionNotifier;
import com.workernotfound.job.global.security.InternalApiProperties;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

/**
 * matching-service의 모집 완료 수신 계약을 호출한다.
 *
 * <p>{@code POST /api/applications/internal/jobs/{jobPostId}/recruitment-completion}에 본문 없이
 * {@code X-Internal-Secret}, {@code X-Job-Version}, {@code Idempotency-Key}를 보낸다. 성공은 2xx이면서
 * 공통 응답의 {@code success=true}인 경우뿐이다. 응답 원문은 보관하거나 로그로 남기지 않고 오류 코드만 꺼낸다.
 *
 * <p>한 번의 호출은 연결 시작부터 본문 수신 완료까지 {@code call-timeout}을 넘지 않는다. JDK {@link HttpClient}의
 * 비동기 교환 하나가 헤더와 본문 수신을 모두 포함하며, 제한시간이 지나면 그 교환을 취소해 연결을 닫는다.
 * {@code HttpURLConnection}의 읽기 타임아웃은 읽기 사이의 간격만 제한하고, 본문 수신 중에는 다른 스레드의
 * {@code disconnect()}로도 소켓이 닫히지 않아 이 용도로 쓰지 않는다.
 */
@Component
@EnableConfigurationProperties(MatchingServiceProperties.class)
public class MatchingRecruitmentCompletionClient implements RecruitmentCompletionNotifier, DisposableBean {

    static final String PATH_FORMAT = "/api/applications/internal/jobs/%d/recruitment-completion";
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final int MAX_CAUSE_DEPTH = 16;
    private static final int HTTP_WORKER_THREADS = 2;
    // 상대 응답의 오류 코드는 형식이 맞을 때만 보존한다. 임의 문자열이 저장소나 로그에 들어가지 않게 한다.
    private static final Pattern SAFE_RESPONSE_CODE = Pattern.compile("[A-Z0-9_-]{1,50}");

    private final URI baseUri;
    private final String internalSecret;
    private final Duration readTimeout;
    private final Duration callTimeout;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor httpExecutor;
    private final HttpClient httpClient;

    public MatchingRecruitmentCompletionClient(
            MatchingServiceProperties properties,
            InternalApiProperties internalApiProperties,
            ObjectMapper objectMapper
    ) {
        this.baseUri = URI.create(properties.baseUrl());
        this.internalSecret = internalApiProperties.secret();
        this.readTimeout = properties.readTimeout();
        this.callTimeout = properties.callTimeout();
        this.objectMapper = objectMapper;
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
    public void notifyRecruitmentCompleted(Long jobPostId, Long jobVersion, String commandId) {
        RecruitmentCompletionNotificationException failure = send(jobPostId, jobVersion, commandId);
        if (failure != null) {
            throw failure;
        }
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

    // 테스트에서 반복 호출 뒤 남은 작업과 스레드를 관찰하기 위한 값
    ThreadPoolExecutor httpExecutor() {
        return httpExecutor;
    }

    private RecruitmentCompletionNotificationException send(Long jobPostId, Long jobVersion, String commandId) {
        CompletableFuture<HttpResponse<byte[]>> exchange = httpClient.sendAsync(
                request(jobPostId, jobVersion, commandId),
                responseInfo -> new BoundedBodySubscriber(MAX_BODY_BYTES));
        try {
            HttpResponse<byte[]> response = exchange.get(callTimeout.toNanos(), TimeUnit.NANOSECONDS);
            return classify(response.statusCode(), readBody(response.body()));
        } catch (TimeoutException exception) {
            // 기다림만 멈추지 않고 진행 중인 교환을 취소한다. JDK 16 이상에서 HTTP/1.1 연결이 닫힌다.
            exchange.cancel(true);
            return new RecruitmentCompletionNotificationException(RecruitmentCompletionFailureType.TIMEOUT, exception);
        } catch (ExecutionException exception) {
            return new RecruitmentCompletionNotificationException(failureTypeOf(exception.getCause()), exception.getCause());
        } catch (CancellationException exception) {
            return new RecruitmentCompletionNotificationException(RecruitmentCompletionFailureType.NETWORK, exception);
        } catch (InterruptedException exception) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            return new RecruitmentCompletionNotificationException(RecruitmentCompletionFailureType.NETWORK, exception);
        }
    }

    private HttpRequest request(Long jobPostId, Long jobVersion, String commandId) {
        return HttpRequest.newBuilder(baseUri.resolve(PATH_FORMAT.formatted(jobPostId)))
                // 응답 헤더까지의 제한. 본문 수신을 포함한 전체 제한은 send()에서 강제한다.
                .timeout(readTimeout)
                // TODO: API Gateway나 mTLS 기반 서비스 간 인증으로 대체한다.
                .header("X-Internal-Secret", internalSecret)
                .header("X-Job-Version", String.valueOf(jobVersion))
                .header("Idempotency-Key", commandId)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private RecruitmentCompletionNotificationException classify(int status, JsonNode body) {
        if (isSuccessful(status) && body != null && body.path("success").asBoolean(false)) {
            return null;
        }
        return new RecruitmentCompletionNotificationException(failureTypeOf(status), status, safeResponseCode(body));
    }

    private RecruitmentCompletionFailureType failureTypeOf(int status) {
        if (status == HttpStatus.CONFLICT.value()) {
            return RecruitmentCompletionFailureType.CONFLICT;
        }
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
            return RecruitmentCompletionFailureType.AUTHENTICATION;
        }
        if (status == HttpStatus.REQUEST_TIMEOUT.value()) {
            return RecruitmentCompletionFailureType.TIMEOUT;
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return RecruitmentCompletionFailureType.THROTTLED;
        }
        if (status >= 500 && status < 600) {
            return RecruitmentCompletionFailureType.SERVER_ERROR;
        }
        // 그 밖의 4xx와 계약과 다른 2xx 응답
        return RecruitmentCompletionFailureType.CONTRACT;
    }

    private RecruitmentCompletionFailureType failureTypeOf(Throwable cause) {
        return isTimeout(cause) ? RecruitmentCompletionFailureType.TIMEOUT : RecruitmentCompletionFailureType.NETWORK;
    }

    private JsonNode readBody(byte[] bytes) {
        try {
            return bytes.length == 0 ? null : objectMapper.readTree(bytes);
        } catch (IOException | RuntimeException exception) {
            // 형식이 맞지 않는 응답은 오류 코드 없이 상태로만 분류한다.
            return null;
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
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("matching-http-");
        threadFactory.setDaemon(true);
        return threadFactory;
    }
}
