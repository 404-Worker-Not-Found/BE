package com.workernotfound.job.external.client.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionFailureType;
import com.workernotfound.job.domain.job.exception.RecruitmentCompletionNotificationException;
import com.workernotfound.job.domain.job.port.RecruitmentCompletionNotifier;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * matching-service의 모집 완료 수신 계약을 호출한다.
 *
 * <p>{@code POST /api/applications/internal/jobs/{jobPostId}/recruitment-completion}에 본문 없이
 * {@code X-Internal-Secret}, {@code X-Job-Version}, {@code Idempotency-Key}를 보낸다. 성공은 2xx이면서
 * 공통 응답의 {@code success=true}인 경우뿐이다. 응답 원문은 보관하거나 로그로 남기지 않고 오류 코드만 꺼낸다.
 */
@Component
public class MatchingRecruitmentCompletionClient implements RecruitmentCompletionNotifier {

    static final String PATH = "/api/applications/internal/jobs/{jobPostId}/recruitment-completion";
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private static final int MAX_CAUSE_DEPTH = 16;
    // 상대 응답의 오류 코드는 형식이 맞을 때만 보존한다. 임의 문자열이 저장소나 로그에 들어가지 않게 한다.
    private static final Pattern SAFE_RESPONSE_CODE = Pattern.compile("[A-Z0-9_-]{1,50}");

    private final RestClient matchingServiceRestClient;
    private final ObjectMapper objectMapper;
    private final Duration maxCallDuration;

    public MatchingRecruitmentCompletionClient(
            @Qualifier("matchingServiceRestClient") RestClient matchingServiceRestClient,
            ObjectMapper objectMapper,
            MatchingServiceProperties properties
    ) {
        this.matchingServiceRestClient = matchingServiceRestClient;
        this.objectMapper = objectMapper;
        this.maxCallDuration = properties.connectTimeout().plus(properties.readTimeout());
    }

    @Override
    public Duration maxCallDuration() {
        return maxCallDuration;
    }

    @Override
    public void notifyRecruitmentCompleted(Long jobPostId, Long jobVersion, String commandId) {
        RecruitmentCompletionNotificationException failure = send(jobPostId, jobVersion, commandId);
        if (failure != null) {
            throw failure;
        }
    }

    private RecruitmentCompletionNotificationException send(Long jobPostId, Long jobVersion, String commandId) {
        try {
            return matchingServiceRestClient.post()
                    .uri(PATH, jobPostId)
                    .header("X-Job-Version", String.valueOf(jobVersion))
                    .header("Idempotency-Key", commandId)
                    .exchange((request, response) -> classify(response.getStatusCode(), readBody(response.getBody())));
        } catch (RestClientException exception) {
            RecruitmentCompletionFailureType type = isTimeout(exception)
                    ? RecruitmentCompletionFailureType.TIMEOUT
                    : RecruitmentCompletionFailureType.NETWORK;
            return new RecruitmentCompletionNotificationException(type, exception);
        }
    }

    private RecruitmentCompletionNotificationException classify(HttpStatusCode status, JsonNode body) {
        if (status.is2xxSuccessful() && body != null && body.path("success").asBoolean(false)) {
            return null;
        }
        return new RecruitmentCompletionNotificationException(
                failureTypeOf(status), status.value(), safeResponseCode(body));
    }

    private RecruitmentCompletionFailureType failureTypeOf(HttpStatusCode status) {
        int value = status.value();
        if (value == HttpStatus.CONFLICT.value()) {
            return RecruitmentCompletionFailureType.CONFLICT;
        }
        if (value == HttpStatus.UNAUTHORIZED.value() || value == HttpStatus.FORBIDDEN.value()) {
            return RecruitmentCompletionFailureType.AUTHENTICATION;
        }
        if (value == HttpStatus.REQUEST_TIMEOUT.value()) {
            return RecruitmentCompletionFailureType.TIMEOUT;
        }
        if (value == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return RecruitmentCompletionFailureType.THROTTLED;
        }
        if (status.is5xxServerError()) {
            return RecruitmentCompletionFailureType.SERVER_ERROR;
        }
        // 그 밖의 4xx와 계약과 다른 2xx 응답
        return RecruitmentCompletionFailureType.CONTRACT;
    }

    private JsonNode readBody(InputStream body) {
        try {
            byte[] bytes = body.readNBytes(MAX_BODY_BYTES);
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
}
