package com.workernotfound.payment.domain.settlement.repository;

import com.workernotfound.payment.domain.settlement.service.WorkTerminationEvent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WorkTerminationRepository {
  private final JdbcTemplate jdbc;
  public record Receipt(String fingerprint, String result) {}
  public record Decision(String fingerprint, String result) {}

  public Optional<Receipt> findReceipt(String eventId) {
    return jdbc.query("SELECT fingerprint,result FROM payment_termination_event_receipts WHERE event_id = ?",
        (rs, row) -> new Receipt(rs.getString(1), rs.getString(2)), eventId).stream().findFirst();
  }

  public Optional<Decision> findDecision(long workId) {
    return jdbc.query("SELECT fingerprint,result FROM payment_work_terminations WHERE work_id = ?",
        (rs, row) -> new Decision(rs.getString(1), rs.getString(2)), workId).stream().findFirst();
  }

  public void insertDecision(WorkTerminationEvent event, long paymentId) {
    jdbc.update("INSERT INTO payment_work_terminations(work_id,matching_id,payment_id,fingerprint,event_type,result,occurred_at,processed_at)"
        + " VALUES (?,?,?,?,?,'REVIEW_REQUIRED',?,CURRENT_TIMESTAMP(6))", event.workId(), event.matchingId(), paymentId,
        event.factFingerprint(), event.eventType(), event.occurredAt());
  }

  public String decide(long workId, String result, String reason) {
    jdbc.update("UPDATE payment_work_terminations SET result=?,reason=?,processed_at=CURRENT_TIMESTAMP(6) WHERE work_id=?", result, reason, workId);
    return result;
  }

  public String recordReceipt(WorkTerminationEvent event, String result) {
    jdbc.update("INSERT INTO payment_termination_event_receipts(event_id,work_id,matching_id,fingerprint,result,received_at)"
        + " VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(6))", event.eventId(), event.workId(), event.matchingId(), event.fingerprint(), result);
    return result;
  }

  public void releaseHistory(long paymentId, long workId) {
    jdbc.update("INSERT INTO payment_status_histories(payment_id,previous_status,next_status,termination_work_id,occurred_at)"
        + " VALUES (?,'LOCKED','RELEASED',?,CURRENT_TIMESTAMP(6))", paymentId, workId);
  }
}
