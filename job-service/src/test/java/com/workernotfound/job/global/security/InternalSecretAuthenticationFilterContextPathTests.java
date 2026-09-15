package com.workernotfound.job.global.security;

import com.workernotfound.job.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "server.servlet.context-path=/job")
class InternalSecretAuthenticationFilterContextPathTests extends IntegrationTestSupport {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void internalApiRejectsRequestWithoutInternalSecretUnderContextPath() throws Exception {
		mockMvc.perform(post("/job/api/jobs/internal/1/application-admissions"))
			.andExpect(status().isUnauthorized());
	}
}
