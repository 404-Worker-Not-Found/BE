package com.workernotfound.job.global.security;

import com.workernotfound.job.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InternalSecretAuthenticationFilterTests extends IntegrationTestSupport {

	private static final String INTERNAL_PATH = "/api/jobs/internal/1/application-admissions";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void internalApiRejectsRequestWithoutInternalSecret() throws Exception {
		mockMvc.perform(post(INTERNAL_PATH))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void internalApiRejectsRequestWithInvalidInternalSecret() throws Exception {
		mockMvc.perform(post(INTERNAL_PATH)
				.header("X-Internal-Secret", "wrong-secret"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void internalApiPassesFilterWithValidInternalSecret() throws Exception {
		mockMvc.perform(post(INTERNAL_PATH)
				.header("X-Internal-Secret", "test-internal-secret"))
			.andExpect(result -> assertNotEquals(401, result.getResponse().getStatus()));
	}
}
