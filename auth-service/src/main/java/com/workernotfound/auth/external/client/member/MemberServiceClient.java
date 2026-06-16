package com.workernotfound.auth.external.client.member;

import com.workernotfound.auth.external.client.member.dto.CreateMemberResponse;
import com.workernotfound.auth.external.client.member.dto.CreateOwnerMemberRequest;
import com.workernotfound.auth.external.client.member.dto.CreateWorkerMemberRequest;
import com.workernotfound.auth.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class MemberServiceClient {

	private static final String CREATE_OWNER_PATH = "/api/members/internal/owners";
	private static final String CREATE_WORKER_PATH = "/api/members/internal/workers";
	private static final ParameterizedTypeReference<ApiResponse<CreateMemberResponse>> CREATE_MEMBER_RESPONSE_TYPE =
		new ParameterizedTypeReference<>() {
		};

	private final RestClient memberServiceRestClient;

	public CreateMemberResponse createOwner(CreateOwnerMemberRequest request) {
		return post(CREATE_OWNER_PATH, request);
	}

	public CreateMemberResponse createWorker(CreateWorkerMemberRequest request) {
		return post(CREATE_WORKER_PATH, request);
	}

	private CreateMemberResponse post(String path, Object request) {
		try {
			ApiResponse<CreateMemberResponse> response = memberServiceRestClient.post()
				.uri(path)
				.body(request)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (httpRequest, clientResponse) -> {
					String responseBody = new String(clientResponse.getBody().readAllBytes());
					throw new MemberServiceClientException(clientResponse.getStatusCode(), responseBody);
				})
				.body(CREATE_MEMBER_RESPONSE_TYPE);
			if (response == null || !response.success() || response.data() == null) {
				throw new MemberServiceClientException("member-service 회원 생성 응답이 올바르지 않습니다.");
			}
			return response.data();
		} catch (RestClientException exception) {
			throw new MemberServiceClientException(exception);
		}
	}
}
