package com.workernotfound.member.external.client.nts;

import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;
import com.workernotfound.member.domain.owner.exception.OwnerErrorCode;
import com.workernotfound.member.domain.owner.service.BusinessVerificationResult;
import com.workernotfound.member.global.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NtsBusinessVerificationServiceTests {

	private MockRestServiceServer server;
	private NtsBusinessVerificationService service;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://api.odcloud.kr");
		server = MockRestServiceServer.bindTo(builder).build();
		service = new NtsBusinessVerificationService(
			builder.build(),
			new NtsBusinessVerificationProperties(
				"https://api.odcloud.kr",
				"test-key",
				Duration.ofSeconds(3),
				Duration.ofSeconds(5)
			)
		);
	}

	@Test
	void verifiesOperatingBusinessAndRemovesHyphensFromRequest() {
		server.expect(once(), requestTo(
			"https://api.odcloud.kr/api/nts-businessman/v1/status?serviceKey=test-key"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().json("{\"b_no\":[\"1234567890\"]}"))
			.andRespond(withSuccess(
				"{\"status_code\":\"OK\",\"data\":[{\"b_no\":\"1234567890\",\"b_stt_cd\":\"01\"}]}",
				MediaType.APPLICATION_JSON
			));

		BusinessVerificationResult result = service.verify("123-45-67890");

		assertThat(result.validFormat()).isTrue();
		assertThat(result.status()).isEqualTo(BusinessVerificationStatus.VERIFIED);
		server.verify();
	}

	@Test
	void rejectsClosedBusiness() {
		server.expect(requestTo("https://api.odcloud.kr/api/nts-businessman/v1/status?serviceKey=test-key"))
			.andRespond(withSuccess(
				"{\"status_code\":\"OK\",\"data\":[{\"b_no\":\"1234567890\",\"b_stt_cd\":\"03\"}]}",
				MediaType.APPLICATION_JSON
			));

		BusinessVerificationResult result = service.verify("1234567890");

		assertThat(result.validFormat()).isTrue();
		assertThat(result.status()).isEqualTo(BusinessVerificationStatus.FAILED);
	}

	@Test
	void reportsUnavailableWhenNtsApiFails() {
		server.expect(requestTo("https://api.odcloud.kr/api/nts-businessman/v1/status?serviceKey=test-key"))
			.andRespond(withServerError());

		assertThatThrownBy(() -> service.verify("1234567890"))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(OwnerErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE));
	}
}
