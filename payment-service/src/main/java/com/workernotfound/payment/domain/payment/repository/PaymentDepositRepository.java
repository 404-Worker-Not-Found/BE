package com.workernotfound.payment.domain.payment.repository;

import com.workernotfound.payment.domain.payment.entity.PaymentDeposit;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PaymentDepositRepository extends JpaRepository<PaymentDeposit, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from PaymentDeposit d where d.jobPostId = :jobPostId")
  Optional<PaymentDeposit> findByJobPostIdForUpdate(@Param("jobPostId") Long jobPostId);
}
