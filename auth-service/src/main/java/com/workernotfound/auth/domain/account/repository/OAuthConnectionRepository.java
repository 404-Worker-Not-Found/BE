package com.workernotfound.auth.domain.account.repository;

import com.workernotfound.auth.domain.account.entity.OAuthConnection;
import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthConnectionRepository extends JpaRepository<OAuthConnection, Long> {

	Optional<OAuthConnection> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);

	boolean existsByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);
}
