package com.workernotfound.payment.domain.payment.repository;

import com.workernotfound.payment.domain.payment.entity.PaymentLock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PaymentLockRepository extends JpaRepository<PaymentLock, Long> {
  interface References {
    Long getMatchingId();
    Long getJobPostId();
  }
  @Query("select p.matchingId as matchingId, p.jobPostId as jobPostId from PaymentLock p where p.id = :id")
  Optional<References> findReferencesById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PaymentLock p where p.id = :id")
  Optional<PaymentLock> findByIdForUpdate(@Param("id") Long id);
}
