package com.workernotfound.auth.domain.token.service;

import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTests extends IntegrationTestSupport {

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private TokenHasher tokenHasher;

	@Test
	void createAndParseAccessToken() {
		String accessToken = jwtTokenProvider.createAccessToken(1L, 2L, MemberRole.OWNER);

		assertThat(jwtTokenProvider.validateAccessToken(accessToken)).isTrue();

		AuthTokenClaims claims = jwtTokenProvider.parseAccessToken(accessToken);
		assertThat(claims.authAccountId()).isEqualTo(1L);
		assertThat(claims.memberId()).isEqualTo(2L);
		assertThat(claims.role()).isEqualTo(MemberRole.OWNER);
	}

	@Test
	void createOpaqueRefreshTokenAndHash() {
		String refreshToken = jwtTokenProvider.createRefreshToken();

		assertThat(refreshToken).isNotBlank();
		assertThat(tokenHasher.hash(refreshToken)).hasSize(64);
	}
}
