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
  private static final RowMapper<SettlementResponse> MAPPER = (rs, row) -> new SettlementResponse(
      rs.getLong("id"), rs.getLong("work_id"), rs.getLong("matching_id"), rs.getLong("job_post_id"),
      rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("status"), rs.getString("hold_reason"),
      rs.getObject("work_completed_at", java.time.LocalDateTime.class),
      rs.getObject("payable_at", java.time.LocalDateTime.class), rs.getObject("paid_at", java.time.LocalDateTime.class));

  public List<SettlementResponse> findParticipantSettlements(long member, String role, Long before, int size) {
    return jdbc.query("SELECT * FROM payment_settlements WHERE " + participantColumn(role)
        + " = ? AND id < ? ORDER BY id DESC LIMIT ?", MAPPER, member, before == null ? Long.MAX_VALUE : before, size);
  }

  public Optional<SettlementResponse> findParticipantSettlement(long member, String role, long id) {
    return jdbc.query("SELECT * FROM payment_settlements WHERE " + participantColumn(role) + " = ? AND id = ?",
        MAPPER, member, id).stream().findFirst();
  }

  private String participantColumn(String role) {
    return switch (role) {
      case "OWNER" -> "owner_member_id";
      case "WORKER" -> "worker_member_id";
      default -> throw new com.workernotfound.payment.global.exception.BusinessException(
          com.workernotfound.payment.global.exception.GlobalErrorCode.FORBIDDEN);
    };
  }
}
