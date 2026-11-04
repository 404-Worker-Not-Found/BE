package com.workernotfound.job.external.client.matching;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;
import com.workernotfound.job.domain.job.exception.ApplicationStateSnapshotException;
import com.workernotfound.job.domain.job.port.ApplicationStateSnapshot;
import com.workernotfound.job.domain.job.port.ApplicationStateSnapshotReader;
import com.workernotfound.job.external.client.BoundedBodySubscriber;
import com.workernotfound.job.external.client.BoundedBodySubscriber.ReceivedBody;
import com.workernotfound.job.global.security.InternalApiProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * matching-service 공고별 지원 상태 스냅샷 계약을 호출한다.
 *
 * <p>{@code GET /api/applications/internal/jobs/{jobPostId}/application-states}에 {@code X-Internal-Secret}을 보낸다.
 * 성공은 200이면서 공통 응답의 {@code success=true}이고, {@code data.jobPostId}가 요청한 공고이며 모든 행의 ID·상태·revision이
 * 계약과 맞는 경우뿐이다. 그 밖의 응답과 통신 실패, {@code call-timeout} 초과는 모두
 * {@link ApplicationStateSnapshotException}이다. 호출자는 다음 실행에서 다시 시도하므로 실패 유형을 나누지 않는다.
 * 응답 원문은 보관하거나 로그로 남기지 않는다.
 */
@Component
@EnableConfigurationProperties(MatchingServiceProperties.class)
public class MatchingApplicationStateClient implements ApplicationStateSnapshotReader {

    static final String PATH_FORMAT = "/api/applications/internal/jobs/%d/application-states";
    // 한 공고의 지원 행은 수십 바이트다. 한도를 넘는 응답은 잘린 것으로 보고 실패로 둔다.
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Set<String> STATUS_NAMES = Arrays.stream(ApplicationStatus.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    private final URI baseUri;
    private final String internalSecret;
    private final MatchingServiceProperties properties;
    private final ObjectReader strictJsonReader;
    private final HttpClient httpClient;

    public MatchingApplicationStateClient(
            MatchingServiceProperties properties,
            InternalApiProperties internalApiProperties,
            ObjectMapper objectMapper
    ) {
        this.baseUri = URI.create(properties.baseUrl());
        this.internalSecret = internalApiProperties.secret();
        this.properties = properties;
        this.strictJsonReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public List<ApplicationStateSnapshot> readApplicationStates(Long jobPostId) {
        CompletableFuture<HttpResponse<ReceivedBody>> exchange = httpClient.sendAsync(
                request(jobPostId), responseInfo -> new BoundedBodySubscriber(MAX_BODY_BYTES));
        try {
            HttpResponse<ReceivedBody> response = exchange.get(properties.callTimeout().toNanos(), TimeUnit.NANOSECONDS);
            return parse(jobPostId, response);
        } catch (InterruptedException exception) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new ApplicationStateSnapshotException("지원 상태 스냅샷 조회가 중단되었습니다.", exception);
        } catch (ExecutionException | TimeoutException exception) {
            exchange.cancel(true);
            throw new ApplicationStateSnapshotException("지원 상태 스냅샷을 받지 못했습니다.", exception);
        }
    }

    private HttpRequest request(Long jobPostId) {
        return HttpRequest.newBuilder(baseUri.resolve(PATH_FORMAT.formatted(jobPostId)))
                .timeout(properties.readTimeout())
                // TODO: API Gateway나 mTLS 기반 서비스 간 인증으로 대체한다.
                .header("X-Internal-Secret", internalSecret)
                .GET()
                .build();
    }

    private List<ApplicationStateSnapshot> parse(Long jobPostId, HttpResponse<ReceivedBody> response) {
        JsonNode body = readBody(response.body());
        if (response.statusCode() != 200 || !body.path("success").isBoolean() || !body.path("success").booleanValue()) {
            throw contractViolation("지원 상태 스냅샷 조회가 실패 응답을 받았습니다.");
        }
        JsonNode data = body.path("data");
        if (!isPositiveLong(data.path("jobPostId")) || data.path("jobPostId").longValue() != jobPostId) {
            throw contractViolation("지원 상태 스냅샷의 공고 ID가 요청과 다릅니다.");
        }
        JsonNode applications = data.path("applications");
        if (!applications.isArray()) {
            throw contractViolation("지원 상태 스냅샷에 지원 목록이 없습니다.");
        }
        return toSnapshots(applications);
    }

    private JsonNode readBody(ReceivedBody body) {
        if (body.isTruncated() || body.bytes().length == 0) {
            throw contractViolation("지원 상태 스냅샷 응답 본문이 비었거나 한도를 넘었습니다.");
        }
        try {
            JsonNode root = strictJsonReader.readTree(body.bytes());
            if (root == null) {
                throw contractViolation("지원 상태 스냅샷 응답 본문이 비어 있습니다.");
            }
            return root;
        } catch (IOException exception) {
            throw new ApplicationStateSnapshotException("지원 상태 스냅샷 응답을 해석할 수 없습니다.", exception);
        }
    }

    private List<ApplicationStateSnapshot> toSnapshots(JsonNode applications) {
        List<ApplicationStateSnapshot> snapshots = new ArrayList<>(applications.size());
        Set<Long> applicationIds = new HashSet<>();
        for (JsonNode application : applications) {
            ApplicationStateSnapshot snapshot = toSnapshot(application);
            if (!applicationIds.add(snapshot.applicationId())) {
                throw contractViolation("지원 상태 스냅샷에 같은 지원 ID가 두 번 있습니다.");
            }
            snapshots.add(snapshot);
        }
        return snapshots;
    }

    private ApplicationStateSnapshot toSnapshot(JsonNode application) {
        JsonNode applicationId = application.path("applicationId");
        JsonNode status = application.path("status");
        JsonNode revision = application.path("revision");
        if (!isPositiveLong(applicationId) || !isPositiveLong(revision)
                || !status.isTextual() || !STATUS_NAMES.contains(status.textValue())) {
            throw contractViolation("지원 상태 스냅샷 행이 계약과 다릅니다.");
        }
        return new ApplicationStateSnapshot(
                applicationId.longValue(), ApplicationStatus.valueOf(status.textValue()), revision.longValue());
    }

    private static boolean isPositiveLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0;
    }

    private static ApplicationStateSnapshotException contractViolation(String message) {
        return new ApplicationStateSnapshotException(message);
    }
}
