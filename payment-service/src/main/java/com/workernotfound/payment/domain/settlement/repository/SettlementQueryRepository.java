package com.workernotfound.payment.domain.settlement.repository;

import com.workernotfound.payment.domain.settlement.dto.SettlementResponse;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SettlementQueryRepository {
  private final JdbcTemplate jdbc;
  private static final String SELECT = "SELECT s.*, COALESCE(p.status='REVIEW_REQUIRED', FALSE) AS payout_review_required"
      + " FROM payment_settlements s LEFT JOIN payment_payout_commands p ON p.settlement_id=s.id";
  private static final RowMapper<SettlementResponse> MAPPER = (rs, row) -> new SettlementResponse(
      rs.getLong("id"), rs.getLong("work_id"), rs.getLong("matching_id"), rs.getLong("job_post_id"),
      rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"), rs.getString("hold_reason"), rs.getBoolean("payout_review_required"),
      rs.getObject("work_completed_at", java.time.LocalDateTime.class),
      rs.getObject("payable_at", java.time.LocalDateTime.class), rs.getObject("paid_at", java.time.LocalDateTime.class));

  public List<SettlementResponse> findParticipantSettlements(long member, String role, Long before, int size) {
    return jdbc.query(SELECT + " WHERE " + participantColumn(role)
        + " = ? AND s.id < ? ORDER BY s.id DESC LIMIT ?", MAPPER, member, before == null ? Long.MAX_VALUE : before, size);
  }

  public Optional<SettlementResponse> findParticipantSettlement(long member, String role, long id) {
    return jdbc.query(SELECT + " WHERE " + participantColumn(role) + " = ? AND s.id = ?",
        MAPPER, member, id).stream().findFirst();
  }

  private String participantColumn(String role) {
    return switch (role) {
      case "OWNER" -> "s.owner_member_id";
      case "WORKER" -> "s.worker_member_id";
      default -> throw new com.workernotfound.payment.global.exception.BusinessException(
          com.workernotfound.payment.global.exception.GlobalErrorCode.FORBIDDEN);
    };
  }
}
