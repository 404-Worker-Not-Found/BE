package com.workernotfound.auth.domain.auth.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class EmailVerificationSender {

	private final RestClient verificationDeliveryRestClient;
	private final VerificationDeliveryProperties properties;

	public void send(String email, String verificationCode) {
		if (!StringUtils.hasText(properties.resendApiKey()) || !StringUtils.hasText(properties.emailFrom())) {
			throw new VerificationDeliveryException("이메일 발송 설정이 없습니다.");
		}
		try {
			Map<?, ?> response = verificationDeliveryRestClient.post()
				.uri("https://api.resend.com/emails")
				.header("Authorization", "Bearer " + properties.resendApiKey())
				.body(Map.of(
					"from", properties.emailFrom(),
					"to", email,
					"subject", "인증번호 안내",
					"text", "인증번호는 " + verificationCode + "입니다. 5분 이내에 입력해 주세요."))
				.retrieve().body(Map.class);
			if (response == null || !(response.get("id") instanceof String id) || !StringUtils.hasText(id)) {
				throw new VerificationDeliveryException("이메일 발송 접수 응답이 올바르지 않습니다.");
			}
		} catch (RestClientException exception) {
			throw new VerificationDeliveryException("이메일 발송에 실패했습니다.", exception);
		}
	}
}
