package com.workernotfound.payment.domain.settlement.repository;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WorkEventReceiptRepository {
  private final JdbcTemplate jdbc;

  public record Receipt(String fingerprint, String result, Long settlementId) {}

  public Optional<Receipt> findByEventId(String eventId) {
    List<Receipt> receipts = jdbc.query(
        "SELECT fingerprint, result, settlement_id FROM payment_work_event_receipts WHERE event_id = ?",
        (rs, row) -> new Receipt(rs.getString(1), rs.getString(2), rs.getObject(3, Long.class)),
        eventId);
    return receipts.stream().findFirst();
  }

  public void save(String eventId, Long workId, Long matchingId, long revision, String fingerprint,
      String result, Long settlementId) {
    jdbc.update(
        "INSERT INTO payment_work_event_receipts (event_id, work_id, matching_id, revision, fingerprint, result,"
            + " settlement_id, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
        eventId,
        workId,
        matchingId,
        revision,
        fingerprint,
        result,
        settlementId);
  }
}
