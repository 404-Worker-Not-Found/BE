package com.workernotfound.auth.domain.token.service;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.enums.MemberStatus;
import com.workernotfound.auth.domain.token.dto.request.TokenReissueRequest;
import com.workernotfound.auth.domain.token.dto.response.TokenResponse;
import com.workernotfound.auth.domain.token.entity.RefreshToken;
import com.workernotfound.auth.domain.token.exception.TokenErrorCode;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TokenService {

	private final RefreshTokenRepository refreshTokenRepository;
	private final JwtTokenProvider jwtTokenProvider;
	private final TokenHasher tokenHasher;

	@Transactional
	public TokenResponse reissue(TokenReissueRequest request) {
		LocalDateTime now = LocalDateTime.now();
		RefreshToken oldRefreshToken = findUsableRefreshToken(request.refreshToken(), now);
		validateActiveAccount(oldRefreshToken);
		validateDevice(oldRefreshToken, request.deviceId());

		String newRefreshToken = jwtTokenProvider.createRefreshToken();
		RefreshToken savedRefreshToken =
				saveRefreshToken(
						oldRefreshToken.getAuthAccount(), request.deviceId(), newRefreshToken, now);
		oldRefreshToken.replace(savedRefreshToken.getId(), now);

		String accessToken =
				jwtTokenProvider.createAccessToken(
						oldRefreshToken.getAuthAccount().getId(),
						oldRefreshToken.getMemberId(),
						oldRefreshToken.getAuthAccount().getRole());
		return jwtTokenProvider.createTokenResponse(accessToken, newRefreshToken);
	}

	@Transactional
	public TokenResponse issue(AuthAccount authAccount, String deviceId) {
		LocalDateTime now = LocalDateTime.now();
		String accessToken =
				jwtTokenProvider.createAccessToken(
						authAccount.getId(), authAccount.getMemberId(), authAccount.getRole());
		String refreshToken = jwtTokenProvider.createRefreshToken();
		saveRefreshToken(authAccount, deviceId, refreshToken, now);
		return jwtTokenProvider.createTokenResponse(accessToken, refreshToken);
	}

	private RefreshToken findUsableRefreshToken(String rawRefreshToken, LocalDateTime now) {
		String tokenHash = tokenHasher.hash(rawRefreshToken);
		RefreshToken refreshToken =
				refreshTokenRepository
						.findByTokenHashForUpdate(tokenHash)
						.orElseThrow(() -> new RefreshTokenException(TokenErrorCode.REFRESH_TOKEN_NOT_FOUND));
		if (refreshToken.getRevokedAt() != null) {
			throw new RefreshTokenException(TokenErrorCode.REFRESH_TOKEN_REVOKED);
		}
		if (!refreshToken.getExpiresAt().isAfter(now)) {
			throw new RefreshTokenException(TokenErrorCode.REFRESH_TOKEN_EXPIRED);
		}
		refreshToken.markUsed(now);
		return refreshToken;
	}

	private void validateActiveAccount(RefreshToken refreshToken) {
		if (refreshToken.getAuthAccount().getStatus() != MemberStatus.ACTIVE) {
			throw new RefreshTokenException(TokenErrorCode.ACCOUNT_NOT_ACTIVE);
		}
	}

	private void validateDevice(RefreshToken refreshToken, String deviceId) {
		if (!refreshToken.getDeviceId().equals(deviceId)) {
			throw new RefreshTokenException(TokenErrorCode.DEVICE_MISMATCH);
		}
	}

	private RefreshToken saveRefreshToken(
			AuthAccount authAccount, String deviceId, String rawRefreshToken, LocalDateTime now) {
		RefreshToken refreshToken =
				RefreshToken.builder()
						.authAccount(authAccount)
						.memberId(authAccount.getMemberId())
						.deviceId(deviceId)
						.tokenHash(tokenHasher.hash(rawRefreshToken))
						.expiresAt(now.plusSeconds(jwtTokenProvider.getRefreshTokenExpiresIn()))
						.lastUsedAt(now)
						.build();
		return refreshTokenRepository.save(refreshToken);
	}
}
