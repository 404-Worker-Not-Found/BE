package com.workernotfound.auth.domain.account.repository;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthAccountRepository extends JpaRepository<AuthAccount, Long> {

	Optional<AuthAccount> findByEmail(String email);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from AuthAccount account where account.email = :email")
	Optional<AuthAccount> findByEmailForUpdate(@Param("email") String email);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from AuthAccount account where account.id = :id")
	Optional<AuthAccount> findByIdForUpdate(@Param("id") Long id);

	boolean existsByEmail(String email);

	Optional<AuthAccount> findByMemberId(Long memberId);
}
