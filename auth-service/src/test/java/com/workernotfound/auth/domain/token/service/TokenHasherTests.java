package com.workernotfound.auth.domain.token.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHasherTests {

	private final TokenHasher tokenHasher = new TokenHasher();

	@Test
	void hashIsDeterministicSha256Hex() {
		String firstHash = tokenHasher.hash("refresh-token");
		String secondHash = tokenHasher.hash("refresh-token");

		assertThat(firstHash).isEqualTo(secondHash);
		assertThat(firstHash).hasSize(64);
		assertThat(firstHash).doesNotContain("refresh-token");
	}

	@Test
	void hashDiffersByRawToken() {
		assertThat(tokenHasher.hash("refresh-token-1"))
			.isNotEqualTo(tokenHasher.hash("refresh-token-2"));
	}
}
