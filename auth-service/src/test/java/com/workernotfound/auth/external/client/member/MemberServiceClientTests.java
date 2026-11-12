package com.workernotfound.auth.external.client.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.MockRestServiceServer.bindTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.auth.external.client.member.dto.CreateOwnerMemberRequest;
import com.workernotfound.auth.external.client.member.dto.MemberRole;
import com.workernotfound.auth.global.exception.GlobalExceptionHandler;
import com.workernotfound.auth.global.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
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
		server
				.expect(requestTo("http://member-service/api/members/internal/owners"))
				.andRespond(
						withStatus(HttpStatus.BAD_REQUEST)
								.contentType(MediaType.APPLICATION_JSON)
								.body("{\"code\":\"OWNER-400-002\"}"));

		assertThatThrownBy(() -> client.createOwner(ownerRequest()))
				.isInstanceOfSatisfying(
						MemberServiceClientException.class,
						exception ->
								assertThat(exception.getErrorCode())
										.isEqualTo(MemberServiceErrorCode.BUSINESS_NOT_OPERATING));
	}

	@Test
	void exposesKnownOwnerValidationErrorThroughAuthResponse() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/signup/owner");

		var response =
				handler.handleMemberServiceClientException(
						new MemberServiceClientException(
								HttpStatus.BAD_REQUEST, MemberServiceErrorCode.BUSINESS_NOT_OPERATING),
						request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		ApiResponse<Void> body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.code()).isEqualTo("OWNER-400-002");
		assertThat(body.message()).isEqualTo("현재 영업 중인 사업자등록번호가 아닙니다.");
	}

	@Test
	void treatsUnknownEmptyErrorResponseAsExternalApiFailure() {
		server
				.expect(requestTo("http://member-service/api/members/internal/owners"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> client.createOwner(ownerRequest()))
				.isInstanceOfSatisfying(
						MemberServiceClientException.class,
						exception -> assertThat(exception.getErrorCode()).isNull());
	}

	@Test
	void knownMemberAndWorkerErrorsKeepTheirStatusAndCodeThroughAuth() {
		for (MemberServiceErrorCode code : MemberServiceErrorCode.values()) {
			server.reset();
			server
					.expect(requestTo("http://member-service/api/members/internal/owners"))
					.andRespond(
							withStatus(code.getHttpStatus())
									.contentType(MediaType.APPLICATION_JSON)
									.body("{\"code\":\"" + code.getCode() + "\"}"));
			assertThatThrownBy(() -> client.createOwner(ownerRequest()))
					.isInstanceOfSatisfying(
							MemberServiceClientException.class,
							failure -> {
								var response =
										new GlobalExceptionHandler()
												.handleMemberServiceClientException(
														failure, new MockHttpServletRequest("POST", "/api/auth/signup/owner"));
								assertThat(response.getStatusCode()).isEqualTo(code.getHttpStatus());
								assertThat(response.getBody().code()).isEqualTo(code.getCode());
							});
			server.verify();
		}
	}

	@Test
	void knownCodeWithWrongStatusDoesNotBecomeBusinessRejection() {
		server
				.expect(requestTo("http://member-service/api/members/internal/owners"))
				.andRespond(
						withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
								.contentType(MediaType.APPLICATION_JSON)
								.body("{\"code\":\"MEMBER-409-001\"}"));
		assertThatThrownBy(() -> client.createOwner(ownerRequest()))
				.isInstanceOfSatisfying(
						MemberServiceClientException.class,
						failure -> {
							assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
							assertThat(failure.getErrorCode()).isNull();
						});
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
				null);
	}

	@Test
	void forwardsGenericValidationAs400WithOnlySafeFieldReasons(CapturedOutput output) {
		server.expect(requestTo("http://member-service/api/members/internal/owners"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("""
					{"code":"GLOBAL-400-002","message":"private-message",
					 "reasons":{"phoneNumber":"010-1234-5678 private-token",
					 "storeLocation.latitude":"private-address","internalSecret":"private-secret"}}
					"""));

		assertThatThrownBy(() -> client.createOwner(ownerRequest()))
			.isInstanceOfSatisfying(MemberServiceClientException.class, failure -> {
				var response = new GlobalExceptionHandler().handleMemberServiceClientException(
					failure, new MockHttpServletRequest("POST", "/api/auth/signup/owner"));
				assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(response.getBody().code()).isEqualTo("GLOBAL-400-002");
				assertThat(response.getBody().reasons()).containsOnlyKeys("phoneNumber", "storeLocation.latitude");
				assertThat(response.getBody().reasons().values()).containsOnly("유효하지 않은 값입니다.");
			});
		assertThat(output.getAll()).contains("service=member-service", "method=POST",
			"path=/api/members/internal/owners", "status=400", "code=GLOBAL-400-002", "elapsedMs=")
			.doesNotContain("private-", "010-1234-5678", "owner@example.com", "1234567890");
	}

	@Test
	void logsUnknownServerErrorWithoutDisclosingRemoteBody(CapturedOutput output) {
		server.expect(requestTo("http://member-service/api/members/internal/owners"))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"GLOBAL-500-001\",\"message\":\"private-secret\"}"));
		assertThatThrownBy(() -> client.createOwner(ownerRequest()))
			.isInstanceOfSatisfying(MemberServiceClientException.class, failure -> {
				var response = new GlobalExceptionHandler().handleMemberServiceClientException(
					failure, new MockHttpServletRequest("POST", "/api/auth/signup/owner"));
				assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			});
		assertThat(output.getAll()).contains("status=500", "code=GLOBAL-500-001", "failure=HTTP_ERROR")
			.doesNotContain("private-secret");
	}

	@Test
	void distinguishesConnectionFailureAndTimeoutWithoutExceptionMessages(CapturedOutput output) {
		server.expect(requestTo("http://member-service/api/members/internal/owners"))
			.andRespond(request -> { throw new java.net.ConnectException("private-connect-secret"); });
		assertThatThrownBy(() -> client.createOwner(ownerRequest())).isInstanceOf(MemberServiceClientException.class);
		assertThat(output.getAll()).contains("failure=CONNECTION_FAILURE").doesNotContain("private-connect-secret");
		server.reset();
		server.expect(requestTo("http://member-service/api/members/internal/owners"))
			.andRespond(request -> { throw new java.net.SocketTimeoutException("private-timeout-secret"); });
		assertThatThrownBy(() -> client.createOwner(ownerRequest())).isInstanceOf(MemberServiceClientException.class);
		assertThat(output.getAll()).contains("failure=TIMEOUT").doesNotContain("private-timeout-secret");
	}

	@Test
	void distinguishesMalformedSuccessFromInvalidSuccessEnvelope(CapturedOutput output) {
		server.expect(requestTo("http://member-service/api/members/internal/owners"))
			.andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
				.body("{\"success\":true,\"data\":private-secret"));
		assertThatThrownBy(() -> client.createOwner(ownerRequest())).isInstanceOf(MemberServiceClientException.class);
		assertThat(output.getAll()).contains("status=200", "failure=RESPONSE_DECODING")
			.doesNotContain("private-secret");
		server.reset();
		server.expect(requestTo("http://member-service/api/members/internal/owners"))
			.andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
				.body("{\"success\":true,\"status\":200,\"data\":null}"));
		assertThatThrownBy(() -> client.createOwner(ownerRequest())).isInstanceOf(MemberServiceClientException.class);
		assertThat(output.getAll()).contains("failure=INVALID_RESPONSE");
	}

	@Test
	void workerValidationFailureIncludesSafeNestedFieldReason() {
		server.expect(requestTo("http://member-service/api/members/internal/workers"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
				{"code":"GLOBAL-400-002","reasons":{"availableTimes[0].startTime":"private-value"}}
				"""));
		var worker = new com.workernotfound.auth.external.client.member.dto.CreateWorkerMemberRequest(
			"근로자", "worker@example.com", "01012345678", MemberRole.WORKER, 12000, 5, true, null,
			java.util.List.of("CAFE"), java.util.List.of());
		assertThatThrownBy(() -> client.createWorker(worker))
			.isInstanceOfSatisfying(MemberServiceClientException.class, failure -> {
				var response = new GlobalExceptionHandler().handleMemberServiceClientException(failure,
					new MockHttpServletRequest("POST", "/api/auth/signup/worker"));
				assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(response.getBody().reasons()).containsEntry("availableTimes[0].startTime", "유효하지 않은 값입니다.");
			});
	}

	@Test
	void validSuccessResponseIsStillDecoded() {
		server.expect(requestTo("http://member-service/api/members/internal/owners"))
			.andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
				{"success":true,"status":201,"code":"SUCCESS","message":"성공",
				 "data":{"memberId":101,"email":"owner@example.com","phoneNumber":"01012345678",
				 "role":"OWNER","status":"ACTIVE"},"timestamp":"2026-11-09T00:00:00"}
				"""));
		assertThat(client.createOwner(ownerRequest()).memberId()).isEqualTo(101L);
	}

	@Test
	void timeoutWhileReadingBodyRetainsStatus(CapturedOutput output) {
		server.expect(requestTo("http://member-service/api/members/internal/owners"))
			.andRespond(request -> {
				var response = new org.springframework.mock.http.client.MockClientHttpResponse(new byte[0], HttpStatus.OK) {
					@Override
					public java.io.InputStream getBody() {
						return new java.io.InputStream() {
							@Override
							public int read() throws java.io.IOException {
								throw new java.net.SocketTimeoutException("private-read-secret");
							}
						};
					}
				};
				response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
				return response;
			});
		assertThatThrownBy(() -> client.createOwner(ownerRequest()))
			.isInstanceOfSatisfying(MemberServiceClientException.class,
				failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.OK));
		assertThat(output.getAll()).contains("status=200", "failure=TIMEOUT").doesNotContain("private-read-secret");
	}

	@Test
	void malformedAndOversizedRemoteErrorsStay502AndDoNotLeak(CapturedOutput output) {
		for (String body : java.util.List.of("not-json-private-secret", "{\"code\":\"private-secret\"}",
			"{\"code\":\"GLOBAL-400-002\"} trailing-private-secret",
			"{\"code\":\"GLOBAL-400-002\",\"message\":\"" + "x".repeat(8192) + "\"}")) {
			server.reset();
			server.expect(requestTo("http://member-service/api/members/internal/owners"))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(body));
			assertThatThrownBy(() -> client.createOwner(ownerRequest()))
				.isInstanceOfSatisfying(MemberServiceClientException.class, failure -> {
					var response = new GlobalExceptionHandler().handleMemberServiceClientException(failure,
						new MockHttpServletRequest("POST", "/api/auth/signup/owner"));
					assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
				});
		}
		assertThat(output.getAll()).doesNotContain("private-secret", "x".repeat(100));
	}

	@Test
	void compensationLogsTemplatePathAndDoesNotForwardValidation(CapturedOutput output) {
		server.expect(requestTo("http://member-service/api/members/internal/12345"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"GLOBAL-400-002\"}"));
		assertThatThrownBy(() -> client.deleteMemberForSignupCompensation(12345L))
			.isInstanceOfSatisfying(MemberServiceClientException.class, failure -> assertThat(failure.getErrorCode()).isNull());
		assertThat(output.getAll()).contains("method=DELETE", "path=/api/members/internal/{memberId}")
			.doesNotContain("12345");
	}
}
