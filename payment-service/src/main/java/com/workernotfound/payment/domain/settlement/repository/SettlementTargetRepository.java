package com.workernotfound.payment.domain.settlement.repository;

import com.workernotfound.payment.domain.settlement.entity.SettlementTarget;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementTargetRepository extends JpaRepository<SettlementTarget, Long> {
  boolean existsByWorkId(Long workId);
}
