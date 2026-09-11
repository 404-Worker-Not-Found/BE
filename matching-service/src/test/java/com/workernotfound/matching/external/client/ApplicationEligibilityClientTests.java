package com.workernotfound.matching.external.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.matching.external.client.job.JobServiceClient;
import com.workernotfound.matching.external.client.job.JobServiceClientException;
import com.workernotfound.matching.external.client.job.dto.ApplicationAdmissionResponse;
import com.workernotfound.matching.external.client.member.MemberServiceClient;
import com.workernotfound.matching.external.client.member.dto.MemberInternalResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ApplicationEligibilityClientTests {

	@Test
	void readsWrappedMemberResponse() {
		RestClient.Builder builder = RestClient.builder()
			.baseUrl("http://member-service")
			.defaultHeader("X-Internal-Secret", "test-secret");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		MemberServiceClient client = new MemberServiceClient(builder.build(), new ObjectMapper());
		server.expect(requestTo("http://member-service/api/members/internal/20"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("X-Internal-Secret", "test-secret"))
			.andRespond(withSuccess(
				"{\"success\":true,\"status\":200,\"code\":\"SUCCESS\",\"message\":\"ok\","
					+ "\"data\":{\"memberId\":20,\"role\":\"WORKER\",\"status\":\"ACTIVE\"}}",
				MediaType.APPLICATION_JSON
			));

		MemberInternalResponse response = client.getMember(20L);

		assertThat(response.role()).isEqualTo("WORKER");
		server.verify();
	}

	@Test
	void sendsInternalSecretAndIdempotencyKeyForAdmission() {
		RestClient.Builder builder = RestClient.builder()
			.baseUrl("http://job-service")
			.defaultHeader("X-Internal-Secret", "test-secret");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		JobServiceClient client = new JobServiceClient(builder.build(), new ObjectMapper());
		server.expect(requestTo("http://job-service/api/jobs/internal/10/application-admissions"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("X-Internal-Secret", "test-secret"))
			.andExpect(header("Idempotency-Key", "command-id"))
			.andRespond(withSuccess(
				"{\"success\":true,\"status\":200,\"code\":\"SUCCESS\",\"message\":\"ok\","
					+ "\"data\":{\"admissionId\":30,\"jobPostId\":10,"
					+ "\"jobVersion\":1,\"ownerMemberId\":100,\"categoryId\":1,"
					+ "\"workDate\":\"2026-09-12\",\"startTime\":\"09:00:00\","
					+ "\"endTime\":\"18:00:00\",\"latitude\":37.5,\"longitude\":127.0,"
					+ "\"admittedAt\":\"2026-09-11T10:00:00\",\"expiresAt\":\"2026-09-11T10:01:00\"}}",
				MediaType.APPLICATION_JSON
			));

		ApplicationAdmissionResponse response = client.createApplicationAdmission(10L, 20L, "command-id");

		assertThat(response.admissionId()).isEqualTo(30L);
		server.verify();
	}

	@Test
	void preservesJobServiceErrorCode() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://job-service");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		JobServiceClient client = new JobServiceClient(builder.build(), new ObjectMapper());
		server.expect(requestTo("http://job-service/api/jobs/internal/10/application-admissions"))
			.andRespond(withStatus(HttpStatus.CONFLICT)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"APPLICATION_DEADLINE_PASSED\"}"));

		assertThatThrownBy(() -> client.createApplicationAdmission(10L, 20L, "command-id"))
			.isInstanceOfSatisfying(JobServiceClientException.class, exception ->
				assertThat(exception.getResponseCode()).isEqualTo("APPLICATION_DEADLINE_PASSED"));
	}
}
