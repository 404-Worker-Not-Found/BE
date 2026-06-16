package com.workernotfound.auth.domain.token.repository;

import com.workernotfound.auth.domain.token.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select refreshToken from RefreshToken refreshToken where refreshToken.tokenHash = :tokenHash")
	Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

	List<RefreshToken> findAllByAuthAccountIdAndDeviceIdAndRevokedAtIsNull(Long authAccountId, String deviceId);

	List<RefreshToken> findAllByAuthAccountIdAndRevokedAtIsNull(Long authAccountId);
}
