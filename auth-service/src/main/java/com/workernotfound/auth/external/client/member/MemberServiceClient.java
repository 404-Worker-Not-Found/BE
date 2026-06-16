package com.workernotfound.auth.external.client.member;

import com.workernotfound.auth.external.client.member.dto.CreateMemberResponse;
import com.workernotfound.auth.external.client.member.dto.CreateOwnerMemberRequest;
import com.workernotfound.auth.external.client.member.dto.CreateWorkerMemberRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class MemberServiceClient {

	private static final String CREATE_OWNER_PATH = "/api/members/internal/owners";
	private static final String CREATE_WORKER_PATH = "/api/members/internal/workers";

	private final RestClient memberServiceRestClient;

	public CreateMemberResponse createOwner(CreateOwnerMemberRequest request) {
		return post(CREATE_OWNER_PATH, request);
	}

	public CreateMemberResponse createWorker(CreateWorkerMemberRequest request) {
		return post(CREATE_WORKER_PATH, request);
	}

	private CreateMemberResponse post(String path, Object request) {
		try {
			return memberServiceRestClient.post()
				.uri(path)
				.body(request)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (httpRequest, response) -> {
					String responseBody = new String(response.getBody().readAllBytes());
					throw new MemberServiceClientException(response.getStatusCode(), responseBody);
				})
				.body(CreateMemberResponse.class);
		} catch (RestClientException exception) {
			throw new MemberServiceClientException(exception);
		}
	}
}
