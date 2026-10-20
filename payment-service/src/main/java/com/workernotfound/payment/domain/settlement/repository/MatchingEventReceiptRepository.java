package com.workernotfound.payment.domain.settlement.repository;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MatchingEventReceiptRepository {
  private final JdbcTemplate jdbc;

  public record Receipt(String fingerprint, String result) {}

  public Optional<Receipt> findByEventId(String eventId) {
    List<Receipt> receipts = jdbc.query(
        "SELECT fingerprint, result FROM payment_matching_event_receipts WHERE event_id = ?",
        (rs, row) -> new Receipt(rs.getString(1), rs.getString(2)),
        eventId);
    return receipts.stream().findFirst();
  }

  public void save(String eventId, Long matchingId, Long revision, String fingerprint, String result) {
    jdbc.update(
        "INSERT INTO payment_matching_event_receipts (event_id, matching_id, revision, fingerprint, result,"
            + " received_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
        eventId,
        matchingId,
        revision,
        fingerprint,
        result);
  }

  // 정산 대상 기록에 성공하지 못한 확정 이벤트가 있는지 확인한다. 성공 결과는 RECORDED·ALREADY_RECORDED뿐이다.
  public boolean existsRejectedByMatchingId(Long matchingId) {
    Long count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM payment_matching_event_receipts WHERE matching_id = ? AND result NOT IN"
            + " ('RECORDED', 'ALREADY_RECORDED')",
        Long.class,
        matchingId);
    return count != null && count > 0;
  }
}
