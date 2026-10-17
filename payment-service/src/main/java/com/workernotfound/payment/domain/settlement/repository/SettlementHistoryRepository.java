package com.workernotfound.payment.domain.settlement.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementHistoryRepository {
  private final JdbcTemplate jdbc;

  public void record(Long settlementId, String previous, String next, String reason) {
    jdbc.update(
        "INSERT INTO payment_settlement_histories (settlement_id, previous_status, next_status, reason,"
            + " occurred_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
        settlementId,
        previous,
        next,
        reason);
  }
}
