package com.workernotfound.auth.external.client.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.auth.external.client.member.dto.CreateOwnerMemberRequest;
import com.workernotfound.auth.external.client.member.dto.MemberRole;
import com.workernotfound.auth.global.exception.GlobalExceptionHandler;
import com.workernotfound.auth.global.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.MockRestServiceServer.bindTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class MemberServiceClientTests {

	private MockRestServiceServer server;
	private MemberServiceClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://member-service");
		server = bindTo(builder).build();
		client = new MemberServiceClient(builder.build(), new ObjectMapper());
	}

	@Test
	void preservesKnownOwnerValidationError() {
		server.expect(requestTo("http://member-service/api/members/internal/owners"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"OWNER-400-002\"}"));

		assertThatThrownBy(() -> client.createOwner(ownerRequest()))
			.isInstanceOfSatisfying(MemberServiceClientException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(MemberServiceErrorCode.BUSINESS_NOT_OPERATING));
	}

	@Test
	void exposesKnownOwnerValidationErrorThroughAuthResponse() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/signup/owner");

		var response = handler.handleMemberServiceClientException(
			new MemberServiceClientException(HttpStatus.BAD_REQUEST, MemberServiceErrorCode.BUSINESS_NOT_OPERATING),
			request
		);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		ApiResponse<Void> body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.code()).isEqualTo("OWNER-400-002");
		assertThat(body.message()).isEqualTo("현재 영업 중인 사업자등록번호가 아닙니다.");
	}

	@Test
	void treatsUnknownEmptyErrorResponseAsExternalApiFailure() {
		server.expect(requestTo("http://member-service/api/members/internal/owners"))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> client.createOwner(ownerRequest()))
			.isInstanceOfSatisfying(MemberServiceClientException.class, exception ->
				assertThat(exception.getErrorCode()).isNull());
	}

	private CreateOwnerMemberRequest ownerRequest() {
		return new CreateOwnerMemberRequest(
			"점주",
			"owner@example.com",
			"01012345678",
			MemberRole.OWNER,
			"1234567890",
			"가게",
			"CAFE",
			null
		);
	}
}
