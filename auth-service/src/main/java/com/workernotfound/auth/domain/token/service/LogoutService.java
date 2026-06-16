package com.workernotfound.auth.domain.token.service;

import com.workernotfound.auth.domain.token.dto.request.LogoutRequest;
import com.workernotfound.auth.domain.token.entity.RefreshToken;
import com.workernotfound.auth.domain.token.repository.RefreshTokenRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LogoutService {

	private final RefreshTokenRepository refreshTokenRepository;
	private final TokenHasher tokenHasher;

	@Transactional
	public void logout(LogoutRequest request) {
		String tokenHash = tokenHasher.hash(request.refreshToken());
		RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
			.orElseThrow(() -> new RefreshTokenException("refresh token을 찾을 수 없습니다."));
		if (refreshToken.getRevokedAt() == null) {
			refreshToken.revoke(LocalDateTime.now());
		}
	}
}
