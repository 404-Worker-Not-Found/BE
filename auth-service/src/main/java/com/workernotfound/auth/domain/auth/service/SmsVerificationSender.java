package com.workernotfound.auth.domain.auth.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class SmsVerificationSender {

	private final RestClient verificationDeliveryRestClient;
	private final VerificationDeliveryProperties properties;

	public void send(String phoneNumber, String verificationCode) {
		if (!StringUtils.hasText(properties.solapiApiKey())
				|| !StringUtils.hasText(properties.solapiApiSecret())
				|| !StringUtils.hasText(properties.smsFrom())) {
			throw new VerificationDeliveryException("SMS 발송 설정이 없습니다.");
		}
		try {
			Map<?, ?> response = verificationDeliveryRestClient.post()
				.uri("https://api.solapi.com/messages/v4/send-many/detail")
				.header("Authorization", authorization())
				.body(Map.of(
					"messages", List.of(Map.of(
						"to", phoneNumber,
						"from", properties.smsFrom(),
						"text", "인증번호는 " + verificationCode + "입니다. 3분 이내에 입력해 주세요.",
						"type", "SMS")),
					"showMessageList", true))
				.retrieve().body(Map.class);
			if (response == null) {
				throw new VerificationDeliveryException("SMS 발송 접수 응답이 비어 있습니다.", true);
			}
			if (response.get("failedMessageList") instanceof List<?> failed && !failed.isEmpty()) {
				throw new VerificationDeliveryException("SMS 발송 접수가 거절되었습니다.");
			}
			if (!(response.get("messageList") instanceof List<?> messages)
					|| messages.size() != 1 || !(messages.get(0) instanceof Map<?, ?> message)
					|| !"2000".equals(message.get("statusCode"))) {
				throw new VerificationDeliveryException("SMS 발송 접수 응답이 올바르지 않습니다.", true);
			}
		} catch (RestClientException exception) {
			throw new VerificationDeliveryException("SMS 발송에 실패했습니다.", exception, true);
		}
	}

	private String authorization() {
		String date = Instant.now().toString();
		String salt = UUID.randomUUID().toString().replace("-", "");
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(properties.solapiApiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			String signature = HexFormat.of().formatHex(mac.doFinal((date + salt).getBytes(StandardCharsets.UTF_8)));
			return "HMAC-SHA256 apiKey=%s, date=%s, salt=%s, signature=%s"
				.formatted(properties.solapiApiKey(), date, salt, signature);
		} catch (java.security.GeneralSecurityException exception) {
			throw new VerificationDeliveryException("SMS 인증 서명을 생성하지 못했습니다.", exception, false);
		}
	}
}
