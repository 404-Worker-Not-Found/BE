package com.workernotfound.payment.domain.payout.repository;

import com.workernotfound.payment.external.payout.PayoutGateway.Request;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PayoutRepository {
  private final JdbcTemplate jdbc;
  public record References(long settlementId, long matchingId, long jobPostId, long workerId) {}
  public record Command(long settlementId, Request request, String status, String providerId,
      String token, LocalDateTime leaseUntil, LocalDateTime nextAttempt, int attempt) {}
  public record Claim(long settlementId, Request request, String token, boolean firstAttempt) {}

  public Optional<References> findReferences(long id) {
    return jdbc.query("SELECT id, matching_id, job_post_id, worker_member_id FROM payment_settlements WHERE id = ?",
        (rs, row) -> new References(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), id).stream().findFirst();
  }

  public Optional<Command> findCommandForUpdate(long id) {
    return jdbc.query("SELECT * FROM payment_payout_commands WHERE settlement_id = ? FOR UPDATE", (rs, row) ->
        new Command(id, new Request(rs.getString("reference"), rs.getString("recipient"),
            rs.getBigDecimal("amount"), rs.getString("currency")), rs.getString("status"), rs.getString("provider_id"),
            rs.getString("claim_token"), rs.getObject("lease_until", LocalDateTime.class),
            rs.getObject("next_attempt_at", LocalDateTime.class), rs.getInt("attempt")), id).stream().findFirst();
  }

  public boolean hasCommand(long id) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM payment_payout_commands WHERE settlement_id = ?", Long.class, id) > 0;
  }

  public void insert(long id, Request request, String token, LocalDateTime now) {
    jdbc.update("INSERT INTO payment_payout_commands (settlement_id, reference, recipient, amount, currency, status,"
        + " claim_token, lease_until, next_attempt_at, attempt, created_at, updated_at)"
        + " VALUES (?, ?, ?, ?, ?, 'AWAITING_RESULT', ?, ?, ?, 1, ?, ?)", id, request.reference(), request.recipient(),
        request.amount(), request.currency(), token, now.plusMinutes(1), now, now, now);
  }

  public void claim(long id, String token, LocalDateTime now) {
    jdbc.update("UPDATE payment_payout_commands SET claim_token=?, lease_until=?, attempt=attempt+1, updated_at=?"
        + " WHERE settlement_id=?", token, now.plusMinutes(1), now, id);
  }

  public void resolve(long id, String status, String provider, String reason, LocalDateTime now) {
    jdbc.update("UPDATE payment_payout_commands SET status=?, provider_id=?, reason=?, claim_token=NULL,"
        + " lease_until=NULL, next_attempt_at=?, updated_at=? WHERE settlement_id=?",
        status, provider, reason, now.plusMinutes(1), now, id);
  }

  public List<Long> findDue(LocalDateTime scheduleNow, LocalDateTime utcNow, int limit) {
    return jdbc.queryForList("SELECT s.id FROM payment_settlements s LEFT JOIN payment_payout_commands p"
        + " ON p.settlement_id=s.id WHERE (s.status='RESERVED' AND s.payable_at<=? AND p.settlement_id IS NULL)"
        + " OR (s.status='PAYOUT_REQUESTED' AND p.status='AWAITING_RESULT' AND p.next_attempt_at<=?"
        + " AND (p.lease_until IS NULL OR p.lease_until<=?)) ORDER BY s.id LIMIT ?",
        Long.class, scheduleNow, utcNow, utcNow, limit);
  }
}
