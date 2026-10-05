package com.workernotfound.auth.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class VerificationDeliveryTests {
	private final VerificationDeliveryProperties properties = new VerificationDeliveryProperties(
		"verify@gmail.com", "app-password", "solapi-key", "solapi-secret", "0212345678");

	@Test
	void gmailSendsVerificationCode(CapturedOutput output) {
		JavaMailSender mailSender = mock(JavaMailSender.class);
		new EmailVerificationSender(mailSender, properties).send("person@example.com", "123456");
		ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(message.capture());
		org.assertj.core.api.Assertions.assertThat(message.getValue().getFrom()).isEqualTo("verify@gmail.com");
		org.assertj.core.api.Assertions.assertThat(message.getValue().getTo()).containsExactly("person@example.com");
		org.assertj.core.api.Assertions.assertThat(message.getValue().getText()).contains("123456");
		org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("123456");
	}

	@Test
	void gmailAuthenticationFailureClearsPendingCode(CapturedOutput output) {
		JavaMailSender mailSender = mock(JavaMailSender.class);
		doThrow(new MailAuthenticationException("123456 provider details"))
			.when(mailSender).send(any(SimpleMailMessage.class));
		assertThatThrownBy(() -> new EmailVerificationSender(mailSender, properties)
			.send("person@example.com", "123456"))
			.isInstanceOf(VerificationDeliveryException.class)
			.satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
				((VerificationDeliveryException) exception).isDeliveryUncertain()).isFalse())
			.hasMessageNotContaining("123456");
		org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("123456");
	}

	@Test
	void gmailTransportFailureRetainsPendingCode(CapturedOutput output) {
		JavaMailSender mailSender = mock(JavaMailSender.class);
		doThrow(new MailSendException("123456 provider details"))
			.when(mailSender).send(any(SimpleMailMessage.class));
		assertThatThrownBy(() -> new EmailVerificationSender(mailSender, properties)
			.send("person@example.com", "123456"))
			.isInstanceOf(VerificationDeliveryException.class)
			.satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
				((VerificationDeliveryException) exception).isDeliveryUncertain()).isTrue())
			.hasMessageNotContaining("123456");
		org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("123456");
	}

	@Test
	void solapiAcceptsRegisteredMessage(CapturedOutput output) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.solapi.com/messages/v4/send-many/detail"))
			.andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("HMAC-SHA256 apiKey=solapi-key,")))
			.andExpect(jsonPath("$.messages[0].text").value(org.hamcrest.Matchers.containsString("123456")))
			.andRespond(withSuccess("{\"messageList\":[{\"statusCode\":\"2000\"}],\"failedMessageList\":[]}", MediaType.APPLICATION_JSON));
		new SmsVerificationSender(builder.build(), properties, new ObjectMapper()).send("01012345678", "123456");
		server.verify();
		org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain("123456");
	}

	@Test
	void solapiRejectsPartialFailure() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.solapi.com/messages/v4/send-many/detail"))
			.andRespond(withSuccess("{\"messageList\":[],\"failedMessageList\":[{\"statusCode\":\"3040\"}]}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> new SmsVerificationSender(builder.build(), properties, new ObjectMapper())
			.send("01012345678", "123456"))
			.isInstanceOf(VerificationDeliveryException.class)
			.satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
				((VerificationDeliveryException) exception).isDeliveryUncertain()).isFalse());
		server.verify();
	}

	@Test
	void solapiHttpClientErrorIsDefiniteRejection() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.solapi.com/messages/v4/send-many/detail"))
			.andRespond(withBadRequest().body("{\"errorCode\":\"4000\",\"errorMessage\":\"123456 01012345678\"}")
				.contentType(MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> new SmsVerificationSender(builder.build(), properties, new ObjectMapper())
			.send("01012345678", "123456"))
			.isInstanceOf(VerificationDeliveryException.class)
			.satisfies(exception -> {
				VerificationDeliveryException deliveryException = (VerificationDeliveryException) exception;
				org.assertj.core.api.Assertions.assertThat(deliveryException.isDeliveryUncertain()).isFalse();
				org.assertj.core.api.Assertions.assertThat(deliveryException.getProviderErrorCode()).isEqualTo("4000");
			})
			.hasMessageNotContaining("123456");
		server.verify();
	}

	@Test
	void solapiErrorCodeContainingVerificationCodeIsNotRetained() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.solapi.com/messages/v4/send-many/detail"))
			.andRespond(withBadRequest().body("{\"errorCode\":\"ERR123456\"}"));
		assertThatThrownBy(() -> new SmsVerificationSender(builder.build(), properties, new ObjectMapper())
			.send("01012345678", "123456"))
			.isInstanceOf(VerificationDeliveryException.class)
			.satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
				((VerificationDeliveryException) exception).getProviderErrorCode()).isNull());
		server.verify();
	}

	@Test
	void missingConfigurationFailsClosed() {
		VerificationDeliveryProperties empty = new VerificationDeliveryProperties("", "", "", "", "");
		assertThatThrownBy(() -> new EmailVerificationSender(mock(JavaMailSender.class), empty)
			.send("person@example.com", "123456")).isInstanceOf(VerificationDeliveryException.class);
		assertThatThrownBy(() -> new SmsVerificationSender(RestClient.create(), empty, new ObjectMapper())
			.send("01012345678", "123456")).isInstanceOf(VerificationDeliveryException.class);
	}
}
