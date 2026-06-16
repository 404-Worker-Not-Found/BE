package com.workernotfound.auth.domain.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EmailVerificationSender {

	public void send(String email, String verificationCode) {
		// TODO: Integrate with an email provider.
		log.info("[LOCAL EMAIL VERIFICATION] email={}, verificationCode={}", email, verificationCode);
	}
}
