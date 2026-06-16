package com.workernotfound.auth.domain.auth.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailVerificationSender {

	private final VerificationProperties verificationProperties;

	public void send(String email, String verificationCode) {
		// TODO: Integrate with an email provider.
		if (verificationProperties.logCodeEnabled()) {
			log.info("[LOCAL EMAIL VERIFICATION] email={}, verificationCode={}", email, verificationCode);
		}
	}
}
