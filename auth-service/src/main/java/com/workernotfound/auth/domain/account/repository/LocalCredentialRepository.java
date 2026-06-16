package com.workernotfound.auth.domain.account.repository;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.account.entity.LocalCredential;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocalCredentialRepository extends JpaRepository<LocalCredential, Long> {

	Optional<LocalCredential> findByAuthAccount(AuthAccount authAccount);

	Optional<LocalCredential> findByAuthAccountId(Long authAccountId);
}
