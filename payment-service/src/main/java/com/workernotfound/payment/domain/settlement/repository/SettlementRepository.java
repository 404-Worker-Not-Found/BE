package com.workernotfound.payment.domain.settlement.repository;

import com.workernotfound.payment.domain.settlement.entity.Settlement;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from Settlement s where s.id = :id")
  Optional<Settlement> findByIdForUpdate(@Param("id") Long id);

  @Query("select s.paymentLockId from Settlement s where s.id = :id")
  Optional<Long> findPaymentLockId(@Param("id") Long id);

  Optional<Settlement> findByWorkId(Long workId);

  Optional<Settlement> findByPaymentLockId(Long paymentLockId);
}
