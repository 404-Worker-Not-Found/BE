package com.workernotfound.matching.external.client.member;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.matching.external.client.member.dto.MemberInternalResponse;
import com.workernotfound.matching.global.response.ApiResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MemberServiceClient {

	private static final ParameterizedTypeReference<ApiResponse<MemberInternalResponse>> RESPONSE_TYPE =
		new ParameterizedTypeReference<>() {
		};

	private final RestClient memberServiceRestClient;
	private final ObjectMapper objectMapper;

	public MemberServiceClient(
		@Qualifier("memberServiceRestClient") RestClient memberServiceRestClient,
		ObjectMapper objectMapper
	) {
		this.memberServiceRestClient = memberServiceRestClient;
		this.objectMapper = objectMapper;
	}

	public MemberInternalResponse getMember(Long memberId) {
		try {
			ApiResponse<MemberInternalResponse> response = memberServiceRestClient.get()
				.uri("/api/members/internal/{memberId}", memberId)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
					String body = new String(clientResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
					throw toClientException(clientResponse.getStatusCode(), body);
				})
				.body(RESPONSE_TYPE);
			if (response == null || !response.success() || response.data() == null) {
				throw new MemberServiceClientException("member-service 회원 조회 응답이 올바르지 않습니다.");
			}
			return response.data();
		} catch (RestClientException exception) {
			throw new MemberServiceClientException(exception);
		}
	}

	private MemberServiceClientException toClientException(HttpStatusCode statusCode, String responseBody) {
		try {
			JsonNode response = objectMapper.readTree(responseBody);
			String code = response == null ? null : response.path("code").textValue();
			return new MemberServiceClientException(statusCode, code);
		} catch (JsonProcessingException exception) {
			return new MemberServiceClientException(statusCode, null);
		}
	}
}
