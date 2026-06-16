package com.workernotfound.auth.domain.auth.service;

import org.springframework.stereotype.Component;

@Component
public class SmsVerificationSender {

	public void send(String phoneNumber, String verificationCode) {
		// TODO: Integrate with an SMS provider.
	}
}
