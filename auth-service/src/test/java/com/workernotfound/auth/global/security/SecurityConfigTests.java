package com.workernotfound.auth.global.security;

import com.workernotfound.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityConfigTests extends IntegrationTestSupport {

	@Autowired
	private MockMvcTester mockMvcTester;

	@Autowired
	private MockMvc mockMvc;

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
	void logoutEndpointIsPublic() throws Exception {
		mockMvc.perform(post("/api/auth/logout")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest());
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
