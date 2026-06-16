package com.workernotfound.auth.global.security;

import com.workernotfound.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@AutoConfigureMockMvc
class SecurityConfigTests extends IntegrationTestSupport {

	@Autowired
	private MockMvcTester mockMvcTester;

	@Test
	void openApiDocsArePublic() {
		mockMvcTester.get()
			.uri("/v3/api-docs")
			.exchange()
			.assertThat()
			.hasStatusOk();
	}

	@Test
	void oauthLoginEndpointIsPublic() {
		mockMvcTester.post()
			.uri("/api/auth/oauth2/KAKAO/login")
			.exchange()
			.assertThat()
			.hasStatus4xxClientError();
	}

	@Test
	void protectedApiRequiresAuthentication() {
		mockMvcTester.get()
			.uri("/api/protected")
			.exchange()
			.assertThat()
			.hasStatus4xxClientError();
	}
}
