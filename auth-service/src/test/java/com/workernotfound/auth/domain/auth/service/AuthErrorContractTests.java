package com.workernotfound.auth.domain.auth.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.workernotfound.auth.support.IntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuthErrorContractTests extends IntegrationTestSupport {
	@Autowired MockMvc mvc;

	@Test
	void resendReturns429DomainCode() throws Exception {
		String body = "{\"email\":\"" + UUID.randomUUID() + "@example.com\"}";
		mvc.perform(
						post("/api/auth/email-verifications/send")
								.contentType("application/json")
								.content(body))
				.andExpect(status().isOk());
		mvc.perform(
						post("/api/auth/email-verifications/send")
								.contentType("application/json")
								.content(body))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("AUTH-429-001"));
	}

	@Test
	void missingLoginAndRefreshTokenKeepAuthenticationErrors() throws Exception {
		mvc.perform(
						post("/api/auth/login")
								.contentType("application/json")
								.content(
										"{\"email\":\"missing-error-contract@example.com\",\"password\":\"wrong-password\",\"deviceId\":\"test-device\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH-401-001"));
		mvc.perform(
						post("/api/auth/tokens/reissue")
								.contentType("application/json")
								.content("{\"refreshToken\":\"not-a-refresh-token\",\"deviceId\":\"test-device\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN-401-001"));
	}
}
