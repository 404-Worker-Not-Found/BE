package com.workernotfound.matching.global.security;

import com.workernotfound.matching.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ApplicationSecurityTests extends IntegrationTestSupport {

	private static final String SECRET = "matching-test-jwt-secret-at-least-32-characters";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void applicationApiRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/applications"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void applicationApiRejectsOwnerRole() throws Exception {
		mockMvc.perform(get("/api/applications")
				.header("Authorization", "Bearer " + token("OWNER")))
			.andExpect(status().isForbidden());
	}

	@Test
	void applicationApiAllowsWorkerRole() throws Exception {
		mockMvc.perform(get("/api/applications")
				.header("Authorization", "Bearer " + token("WORKER")))
			.andExpect(status().isOk());
	}

	@Test
	void applicationListRejectsInvalidPage() throws Exception {
		mockMvc.perform(get("/api/applications")
				.param("page", "-1")
				.header("Authorization", "Bearer " + token("WORKER")))
			.andExpect(status().isBadRequest());
	}

	private String token(String role) throws Exception {
		String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
		String payload = encode("{\"authAccountId\":1,\"memberId\":20,\"role\":\"%s\",\"exp\":%d}"
			.formatted(role, Instant.now().plusSeconds(60).getEpochSecond()));
		String unsignedToken = header + "." + payload;
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		String signature = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8)));
		return unsignedToken + "." + signature;
	}

	private String encode(String value) {
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
}
