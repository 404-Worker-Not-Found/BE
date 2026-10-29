package com.workernotfound.job.external.client.member;

import com.fasterxml.jackson.databind.*;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.port.BusinessValidator;
import com.workernotfound.job.external.client.BoundedBodySubscriber;
import com.workernotfound.job.external.client.BoundedBodySubscriber.ReceivedBody;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.global.security.InternalApiProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(MemberServiceProperties.class)
public class MemberBusinessValidator implements BusinessValidator {
    private final HttpClient client;
    private final MemberServiceProperties properties;
    private final String secret;
    private final ObjectReader json;

    public MemberBusinessValidator(MemberServiceProperties properties, InternalApiProperties internal, ObjectMapper mapper) {
        this.properties = properties;
        this.secret = internal.secret();
        this.json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public void validateOwnership(Long businessId, Long ownerId) {
        if (businessId == null || ownerId == null || businessId <= 0 || ownerId <= 0) {
            throw new BusinessException(JobErrorCode.BUSINESS_NOT_OWNED);
        }
        URI endpoint = URI.create(properties.baseUrl()).resolve("/api/members/internal/owners/" + ownerId + "/business");
        var request = HttpRequest.newBuilder(endpoint).header("X-Internal-Secret", secret)
                .timeout(properties.callTimeout()).GET().build();
        var exchange = client.sendAsync(request, info -> new BoundedBodySubscriber(4096));
        try {
            var response = exchange.get(properties.callTimeout().toMillis(), TimeUnit.MILLISECONDS);
            verify(response, businessId, ownerId);
        } catch (InterruptedException exception) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException exception) {
            exchange.cancel(true);
            throw unavailable();
        }
    }

    private void verify(HttpResponse<ReceivedBody> response, Long businessId, Long ownerId) {
        if (response.body().isTruncated()) throw unavailable();
        JsonNode root;
        try { root = json.readTree(response.body().bytes()); }
        catch (IOException exception) { throw unavailable(); }
        if (root == null) throw unavailable();
        if (response.statusCode() == 404 && root.path("success").isBoolean() && !root.path("success").booleanValue()
                && Set.of("MEMBER-404-002", "OWNER-404-001").contains(root.path("code").asText())) {
            throw new BusinessException(JobErrorCode.BUSINESS_NOT_OWNED);
        }
        if (response.statusCode() != 200 || !root.path("success").isBoolean()
                || !root.path("success").booleanValue()) throw unavailable();
        var data = root.path("data");
        if (!integralId(data.path("businessId")) || !integralId(data.path("ownerMemberId"))
                || data.path("ownerMemberId").longValue() != ownerId) throw unavailable();
        if (data.path("businessId").longValue() != businessId) {
            throw new BusinessException(JobErrorCode.BUSINESS_NOT_OWNED);
        }
    }

    private boolean integralId(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0;
    }

    private BusinessException unavailable() { return new BusinessException(JobErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE); }
}
