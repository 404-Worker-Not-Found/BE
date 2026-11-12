package com.workernotfound.auth.domain.auth.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.workernotfound.auth.domain.auth.controller.OAuthController;
import com.workernotfound.auth.domain.auth.controller.SignupController;
import com.workernotfound.auth.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SignupRequestValidationTests {
	private final ObjectMapper mapper = new ObjectMapper();
	private SignupService signupService;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		signupService = mock(SignupService.class);
		mvc = MockMvcBuilders.standaloneSetup(new SignupController(signupService),
			new OAuthController(mock(OAuthLoginService.class), signupService))
			.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/auth/signup/worker", "/api/auth/signup/owner",
		"/api/auth/oauth2/signup/worker", "/api/auth/oauth2/signup/owner"})
	void rejectsFormattedPhoneBeforeSignupSideEffects(String path) throws Exception {
		ObjectNode body = request(path);
		body.put("phoneNumber", "010-1234-5678");
		mvc.perform(post(path).contentType("application/json").content(body.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("GLOBAL-400-002"))
			.andExpect(jsonPath("$.reasons.phoneNumber").exists());
		verifyNoInteractions(signupService);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/auth/signup/worker", "/api/auth/signup/owner",
		"/api/auth/oauth2/signup/worker", "/api/auth/oauth2/signup/owner"})
	void acceptsMemberCompatiblePhoneAndLocation(String path) throws Exception {
		mvc.perform(post(path).contentType("application/json").content(request(path).toString()))
			.andExpect(status().isCreated());
	}

	@ParameterizedTest
	@ValueSource(strings = {"91", "-91", "37.12345678"})
	void rejectsInvalidCoordinatesBeforeMemberCall(String latitude) throws Exception {
		ObjectNode body = request("/api/auth/signup/worker");
		((ObjectNode) body.get("baseLocation")).put("latitude", new java.math.BigDecimal(latitude));
		mvc.perform(post("/api/auth/signup/worker").contentType("application/json").content(body.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reasons['baseLocation.latitude']").exists());
		verifyNoInteractions(signupService);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/auth/signup/owner", "/api/auth/oauth2/signup/owner"})
	void rejectsInvalidBusinessNumberBeforeSignupSideEffects(String path) throws Exception {
		ObjectNode body = request(path);
		body.put("businessRegistrationNumber", "invalid");
		mvc.perform(post(path).contentType("application/json").content(body.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reasons.businessRegistrationNumber").exists());
		verifyNoInteractions(signupService);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/auth/signup/worker", "/api/auth/oauth2/signup/worker"})
	void stillValidatesEachAvailableTimeAfterMovingValidAnnotation(String path) throws Exception {
		ObjectNode body = request(path);
		((ObjectNode) body.get("availableTimes").get(0)).remove("startTime");
		mvc.perform(post(path).contentType("application/json").content(body.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.reasons['availableTimes[0].startTime']").exists());
		verifyNoInteractions(signupService);
	}

	private ObjectNode request(String path) throws Exception {
		ObjectNode body = (ObjectNode) mapper.readTree("""
			{"name":"테스트","phoneNumber":"+821012345678","deviceId":"test-device"}
			""");
		if (path.contains("oauth2")) body.put("signupTicket", "test-ticket");
		else {
			body.put("email", "test@example.com");
			body.put("password", "test-password");
		}
		ObjectNode location = (ObjectNode) mapper.readTree("""
			{"address":"테스트 주소","latitude":37.1234567,"longitude":126.1234567}
			""");
		if (path.endsWith("worker")) {
			body.put("desiredHourlyWage", 12000).put("activityRadiusKm", 5).put("immediatelyAvailable", true);
			body.set("baseLocation", location);
			body.set("preferredBusinessTypes", mapper.readTree("[\"CAFE\"]"));
			body.set("availableTimes", mapper.readTree("""
				[{"dayOfWeek":"MONDAY","startTime":"09:00:00","endTime":"18:00:00"}]
				"""));
		} else {
			body.put("businessRegistrationNumber", "123-45-67890").put("storeName", "가게").put("businessType", "CAFE");
			body.set("storeLocation", location);
		}
		return body;
	}
}
