package com.workernotfound.auth.domain.auth.service;

import org.springframework.stereotype.Component;

@Component
public class EmailVerificationSender {

	public void send(String email, String verificationCode) {
		// TODO: Integrate with an email provider.
	}
}
