package com.workernotfound.payment.domain.settlement.repository;

import com.workernotfound.payment.domain.settlement.entity.Settlement;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {
  Optional<Settlement> findByWorkId(Long workId);

  Optional<Settlement> findByPaymentLockId(Long paymentLockId);
}
