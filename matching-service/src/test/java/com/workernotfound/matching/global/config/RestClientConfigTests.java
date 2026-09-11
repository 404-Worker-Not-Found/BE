package com.workernotfound.matching.global.config;

import com.workernotfound.matching.external.client.member.MemberServiceProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientConfigTests {

	private final RestClientConfig restClientConfig = new RestClientConfig();

	@Test
	void allowsLocalHttpServiceUrl() {
		MemberServiceProperties properties = properties("http://localhost:8082");

		assertThatCode(() -> restClientConfig.memberServiceRestClient(properties))
			.doesNotThrowAnyException();
	}

	@Test
	void allowsHttpsServiceUrl() {
		MemberServiceProperties properties = properties("https://member-service.example.com");

		assertThatCode(() -> restClientConfig.memberServiceRestClient(properties))
			.doesNotThrowAnyException();
	}

	@Test
	void rejectsNonLoopbackHttpServiceUrl() {
		MemberServiceProperties properties = properties("http://member-service.example.com");

		assertThatThrownBy(() -> restClientConfig.memberServiceRestClient(properties))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("내부 서비스 URL은 HTTPS 또는 로컬 루프백 HTTP 주소여야 합니다.");
	}

	private MemberServiceProperties properties(String baseUrl) {
		return new MemberServiceProperties(
			baseUrl,
			Duration.ofSeconds(3),
			Duration.ofSeconds(5),
			"test-secret"
		);
	}
}
