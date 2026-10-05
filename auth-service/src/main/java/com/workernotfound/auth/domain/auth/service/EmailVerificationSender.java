package com.workernotfound.auth.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class EmailVerificationSender {

	private final JavaMailSender mailSender;
	private final VerificationDeliveryProperties properties;

	public void send(String email, String verificationCode) {
		if (!StringUtils.hasText(properties.emailUsername())
				|| !StringUtils.hasText(properties.emailPassword())) {
			throw new VerificationDeliveryException("이메일 발송 설정이 없습니다.");
		}
		try {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setFrom(properties.emailUsername());
			message.setTo(email);
			message.setSubject("인증번호 안내");
			message.setText("인증번호는 " + verificationCode + "입니다. 5분 이내에 입력해 주세요.");
			mailSender.send(message);
		} catch (MailAuthenticationException | MailParseException | MailPreparationException exception) {
			throw new VerificationDeliveryException("이메일 발송이 거절되었습니다.");
		} catch (MailException exception) {
			throw new VerificationDeliveryException("이메일 발송에 실패했습니다.", true);
		}
	}
}
