package com.workernotfound.auth.domain.auth.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmsVerificationSender {

	private final VerificationProperties verificationProperties;

	public void send(String phoneNumber, String verificationCode) {
		// TODO: Integrate with an SMS provider.
		if (verificationProperties.logCodeEnabled()) {
			log.info("[LOCAL SMS VERIFICATION] phoneNumber={}, verificationCode={}", phoneNumber, verificationCode);
		}
	}
}
