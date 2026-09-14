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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

	@Test
	void ownerApplicantApiAllowsOwnerRole() throws Exception {
		mockMvc.perform(get("/api/jobs/10/applications")
				.header("Authorization", "Bearer " + token("OWNER")))
			.andExpect(status().isOk());
	}

	@Test
	void ownerApplicantApiRejectsWorkerRole() throws Exception {
		mockMvc.perform(get("/api/jobs/10/applications")
				.header("Authorization", "Bearer " + token("WORKER")))
			.andExpect(status().isForbidden());
	}

	@Test
	void ownerApplicantListRejectsInvalidScoreBatchId() throws Exception {
		mockMvc.perform(get("/api/jobs/10/applications")
				.param("scoreBatchId", "0")
				.header("Authorization", "Bearer " + token("OWNER")))
			.andExpect(status().isBadRequest());
	}

	@Test
	void manualMatchingApiRequiresOwnerRole() throws Exception {
		mockMvc.perform(post("/api/jobs/10/applications/20/matchings")
				.contentType("application/json")
				.content("{}")
				.header("Authorization", "Bearer " + token("WORKER")))
			.andExpect(status().isForbidden());
	}

	@Test
	void manualMatchingApiRequiresAuthentication() throws Exception {
		mockMvc.perform(post("/api/jobs/10/applications/20/matchings")
				.contentType("application/json")
				.content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void manualMatchingApiRejectsNonPositiveJobPostId() throws Exception {
		mockMvc.perform(post("/api/jobs/0/applications/20/matchings")
				.contentType("application/json")
				.content("{}")
				.header("Authorization", "Bearer " + token("OWNER")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("GLOBAL-400-002"));
	}

	@Test
	void manualMatchingApiRejectsNonPositiveApplicationId() throws Exception {
		mockMvc.perform(post("/api/jobs/10/applications/0/matchings")
				.contentType("application/json")
				.content("{}")
				.header("Authorization", "Bearer " + token("OWNER")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("GLOBAL-400-002"));
	}

	@Test
	void manualMatchingApiRejectsNonPositiveScoreBatchId() throws Exception {
		mockMvc.perform(post("/api/jobs/10/applications/20/matchings")
				.contentType("application/json")
				.content("{\"scoreBatchId\":0}")
				.header("Authorization", "Bearer " + token("OWNER")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("GLOBAL-400-002"));
	}

	@Test
	void workerMatchingApiRejectsOwnerRole() throws Exception {
		mockMvc.perform(get("/api/matchings")
				.header("Authorization", "Bearer " + token("OWNER")))
			.andExpect(status().isForbidden());
	}

	@Test
	void workerMatchingApiAllowsWorkerRole() throws Exception {
		mockMvc.perform(get("/api/matchings")
				.header("Authorization", "Bearer " + token("WORKER")))
			.andExpect(status().isOk());
	}

	@Test
	void workerMatchingApiRejectsNonPositiveMatchingId() throws Exception {
		mockMvc.perform(get("/api/matchings/0")
				.header("Authorization", "Bearer " + token("WORKER")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("GLOBAL-400-002"));
	}

	@Test
	void workerMatchingApiRejectsInvalidPage() throws Exception {
		mockMvc.perform(get("/api/matchings")
				.param("page", "-1")
				.header("Authorization", "Bearer " + token("WORKER")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("GLOBAL-400-002"));
	}

	@Test
	void workerMatchingApiAcceptsMaximumPageSize() throws Exception {
		mockMvc.perform(get("/api/matchings")
				.param("size", "100")
				.header("Authorization", "Bearer " + token("WORKER")))
			.andExpect(status().isOk());
	}

	@Test
	void workerMatchingApiRejectsPageSizeOverMaximum() throws Exception {
		mockMvc.perform(get("/api/matchings")
				.param("size", "101")
				.header("Authorization", "Bearer " + token("WORKER")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("GLOBAL-400-002"));
	}

	@Test
	void matchingAcceptApiRejectsOwnerRole() throws Exception {
		mockMvc.perform(patch("/api/matchings/10/accept")
				.header("Authorization", "Bearer " + token("OWNER")))
			.andExpect(status().isForbidden());
	}

	@Test
	void matchingAcceptApiRejectsNonPositiveMatchingId() throws Exception {
		mockMvc.perform(patch("/api/matchings/0/accept")
				.header("Authorization", "Bearer " + token("WORKER")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("GLOBAL-400-002"));
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
