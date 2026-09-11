package com.workernotfound.matching.external.client.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.matching.external.client.job.dto.ApplicationAdmissionRequest;
import com.workernotfound.matching.external.client.job.dto.ApplicationAdmissionResponse;
import com.workernotfound.matching.global.response.ApiResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class JobServiceClient {

	private static final ParameterizedTypeReference<ApiResponse<ApplicationAdmissionResponse>> RESPONSE_TYPE =
		new ParameterizedTypeReference<>() {
		};

	private final RestClient jobServiceRestClient;
	private final ObjectMapper objectMapper;

	public JobServiceClient(
		@Qualifier("jobServiceRestClient") RestClient jobServiceRestClient,
		ObjectMapper objectMapper
	) {
		this.jobServiceRestClient = jobServiceRestClient;
		this.objectMapper = objectMapper;
	}

	public ApplicationAdmissionResponse createApplicationAdmission(
		Long jobPostId,
		Long workerMemberId,
		String idempotencyKey
	) {
		try {
			ApiResponse<ApplicationAdmissionResponse> response = jobServiceRestClient.post()
				.uri("/api/jobs/internal/{jobPostId}/application-admissions", jobPostId)
				.header("Idempotency-Key", idempotencyKey)
				.body(new ApplicationAdmissionRequest(workerMemberId))
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
					String body = new String(clientResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
					throw toClientException(clientResponse.getStatusCode(), body);
				})
				.body(RESPONSE_TYPE);
			if (response == null || !response.success() || response.data() == null) {
				throw new JobServiceClientException("job-service 지원 승인 응답이 올바르지 않습니다.");
			}
			return response.data();
		} catch (RestClientException exception) {
			throw new JobServiceClientException(exception);
		}
	}

	private JobServiceClientException toClientException(HttpStatusCode statusCode, String responseBody) {
		try {
			JsonNode response = objectMapper.readTree(responseBody);
			String code = response == null ? null : response.path("code").textValue();
			return new JobServiceClientException(statusCode, code);
		} catch (JsonProcessingException exception) {
			return new JobServiceClientException(statusCode, null);
		}
	}
}
