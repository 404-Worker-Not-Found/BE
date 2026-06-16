package com.workernotfound.member.global.security;

import com.workernotfound.member.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InternalSecretAuthenticationFilterTests extends IntegrationTestSupport {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void internalApiRejectsRequestWithoutInternalSecret() throws Exception {
		mockMvc.perform(post("/api/members/internal/owners")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void internalApiPassesFilterWithValidInternalSecret() throws Exception {
		mockMvc.perform(post("/api/members/internal/owners")
				.header("X-Internal-Secret", "test-internal-secret")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void myMemberApiRequiresJwtAuthentication() throws Exception {
		mockMvc.perform(get("/api/members/me"))
			.andExpect(status().isUnauthorized());
	}
}
