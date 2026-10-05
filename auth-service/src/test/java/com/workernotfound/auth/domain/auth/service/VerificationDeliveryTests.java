package com.workernotfound.auth.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class VerificationDeliveryTests {
	private final VerificationDeliveryProperties properties = new VerificationDeliveryProperties(
		"resend-key", "verify@example.com", "solapi-key", "solapi-secret", "0212345678");

	@Test
	void resendAcceptsEmail(CapturedOutput output) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.resend.com/emails"))
			.andExpect(header("Authorization", "Bearer resend-key"))
			.andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("123456")))
			.andRespond(withSuccess("{\"id\":\"email-id\"}", MediaType.APPLICATION_JSON));
		new EmailVerificationSender(builder.build(), properties).send("person@example.com", "123456");
		server.verify();
		org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("123456");
	}

	@Test
	void resendErrorHidesCode() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.resend.com/emails"))
			.andRespond(withServerError().body("123456 provider details"));
		assertThatThrownBy(() -> new EmailVerificationSender(builder.build(), properties)
			.send("person@example.com", "123456"))
			.isInstanceOf(VerificationDeliveryException.class)
			.satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
				((VerificationDeliveryException) exception).isDeliveryUncertain()).isTrue())
			.hasMessageNotContaining("123456");
		server.verify();
	}

	@Test
	void solapiAcceptsRegisteredMessage(CapturedOutput output) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.solapi.com/messages/v4/send-many/detail"))
			.andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("HMAC-SHA256 apiKey=solapi-key,")))
			.andExpect(jsonPath("$.messages[0].text").value(org.hamcrest.Matchers.containsString("123456")))
			.andRespond(withSuccess("{\"messageList\":[{\"statusCode\":\"2000\"}],\"failedMessageList\":[]}", MediaType.APPLICATION_JSON));
		new SmsVerificationSender(builder.build(), properties).send("01012345678", "123456");
		server.verify();
		org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("123456");
	}

	@Test
	void solapiRejectsPartialFailure() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.solapi.com/messages/v4/send-many/detail"))
			.andRespond(withSuccess("{\"messageList\":[],\"failedMessageList\":[{\"statusCode\":\"3040\"}]}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> new SmsVerificationSender(builder.build(), properties)
			.send("01012345678", "123456"))
			.isInstanceOf(VerificationDeliveryException.class)
			.satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
				((VerificationDeliveryException) exception).isDeliveryUncertain()).isFalse());
		server.verify();
	}

	@Test
	void missingConfigurationFailsClosed() {
		VerificationDeliveryProperties empty = new VerificationDeliveryProperties("", "", "", "", "");
		assertThatThrownBy(() -> new EmailVerificationSender(RestClient.create(), empty)
			.send("person@example.com", "123456")).isInstanceOf(VerificationDeliveryException.class);
		assertThatThrownBy(() -> new SmsVerificationSender(RestClient.create(), empty)
			.send("01012345678", "123456")).isInstanceOf(VerificationDeliveryException.class);
	}
}
