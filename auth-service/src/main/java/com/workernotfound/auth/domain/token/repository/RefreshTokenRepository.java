package com.workernotfound.auth.domain.token.repository;

import com.workernotfound.auth.domain.token.entity.RefreshToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	List<RefreshToken> findAllByAuthAccountIdAndDeviceIdAndRevokedAtIsNull(Long authAccountId, String deviceId);

	List<RefreshToken> findAllByAuthAccountIdAndRevokedAtIsNull(Long authAccountId);
}
