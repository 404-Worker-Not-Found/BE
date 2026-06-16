package com.workernotfound.auth.domain.token.service;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.SignupType;
import com.workernotfound.auth.domain.account.repository.AuthAccountRepository;
import com.workernotfound.auth.domain.token.dto.request.TokenReissueRequest;
import com.workernotfound.auth.domain.token.dto.response.TokenResponse;
import com.workernotfound.auth.domain.token.entity.RefreshToken;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import com.workernotfound.auth.support.IntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class TokenServiceTests extends IntegrationTestSupport {

	@Autowired
	private TokenService tokenService;

	@Autowired
	private AuthAccountRepository authAccountRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private TokenHasher tokenHasher;

	@Test
	void reissueRotatesRefreshToken() {
		AuthAccount authAccount = saveAuthAccount("rotation@example.com", 101L);
		TokenResponse issuedToken = tokenService.issue(authAccount, "device-1");

		TokenResponse reissuedToken = tokenService.reissue(new TokenReissueRequest(
			issuedToken.refreshToken(),
			"device-1"
		));

		assertThat(reissuedToken.refreshToken()).isNotEqualTo(issuedToken.refreshToken());
		RefreshToken oldRefreshToken = refreshTokenRepository.findByTokenHash(tokenHasher.hash(issuedToken.refreshToken()))
			.orElseThrow();
		RefreshToken newRefreshToken = refreshTokenRepository.findByTokenHash(tokenHasher.hash(reissuedToken.refreshToken()))
			.orElseThrow();
		assertThat(oldRefreshToken.getRevokedAt()).isNotNull();
		assertThat(oldRefreshToken.getReplacedByTokenId()).isEqualTo(newRefreshToken.getId());
		assertThat(newRefreshToken.getRevokedAt()).isNull();
		assertThat(newRefreshToken.getDeviceId()).isEqualTo("device-1");
	}

	@Test
	void reissueFailsWithRevokedRefreshToken() {
		AuthAccount authAccount = saveAuthAccount("revoked@example.com", 102L);
		TokenResponse issuedToken = tokenService.issue(authAccount, "device-1");
		tokenService.reissue(new TokenReissueRequest(issuedToken.refreshToken(), "device-1"));

		assertThatThrownBy(() -> tokenService.reissue(new TokenReissueRequest(issuedToken.refreshToken(), "device-1")))
			.isInstanceOf(RefreshTokenException.class)
			.hasMessage("이미 폐기된 refresh token입니다.");
	}

	@Test
	void reissueFailsWithDifferentDeviceId() {
		AuthAccount authAccount = saveAuthAccount("device@example.com", 103L);
		TokenResponse issuedToken = tokenService.issue(authAccount, "device-1");

		assertThatThrownBy(() -> tokenService.reissue(new TokenReissueRequest(issuedToken.refreshToken(), "device-2")))
			.isInstanceOf(RefreshTokenException.class)
			.hasMessage("refresh token의 deviceId가 일치하지 않습니다.");
	}

	@Test
	void issueStoresOnlyRefreshTokenHash() {
		AuthAccount authAccount = saveAuthAccount("hash-only@example.com", 104L);
		TokenResponse issuedToken = tokenService.issue(authAccount, "device-1");

		List<RefreshToken> refreshTokens = refreshTokenRepository.findAllByAuthAccountIdAndRevokedAtIsNull(
			authAccount.getId()
		);

		assertThat(refreshTokens).hasSize(1);
		assertThat(refreshTokens.get(0).getTokenHash()).isEqualTo(tokenHasher.hash(issuedToken.refreshToken()));
		assertThat(refreshTokens.get(0).getTokenHash()).isNotEqualTo(issuedToken.refreshToken());
	}

	private AuthAccount saveAuthAccount(String email, Long memberId) {
		return authAccountRepository.save(AuthAccount.builder()
			.memberId(memberId)
			.email(email)
			.role(MemberRole.OWNER)
			.signupType(SignupType.LOCAL)
			.build());
	}
}
