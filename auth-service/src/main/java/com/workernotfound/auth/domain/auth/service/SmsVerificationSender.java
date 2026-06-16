package com.workernotfound.auth.domain.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmsVerificationSender {

	public void send(String phoneNumber, String verificationCode) {
		// TODO: Integrate with an SMS provider.
		log.info("[LOCAL SMS VERIFICATION] phoneNumber={}, verificationCode={}", phoneNumber, verificationCode);
	}
}
