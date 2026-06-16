package com.workernotfound.auth.domain.auth.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class VerificationCodeGenerator {

	private static final int CODE_BOUND = 1_000_000;
	private static final String CODE_FORMAT = "%06d";

	private final SecureRandom secureRandom = new SecureRandom();

	public String generate() {
		return CODE_FORMAT.formatted(secureRandom.nextInt(CODE_BOUND));
	}
}
