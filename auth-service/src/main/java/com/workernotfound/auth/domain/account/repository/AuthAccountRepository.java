package com.workernotfound.auth.domain.account.repository;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthAccountRepository extends JpaRepository<AuthAccount, Long> {

	Optional<AuthAccount> findByEmail(String email);

	boolean existsByEmail(String email);

	Optional<AuthAccount> findByMemberId(Long memberId);
}
