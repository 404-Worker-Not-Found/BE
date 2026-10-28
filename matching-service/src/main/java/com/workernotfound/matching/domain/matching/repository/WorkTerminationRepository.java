package com.workernotfound.matching.domain.matching.repository;

import com.workernotfound.matching.domain.matching.event.WorkTerminationEvent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WorkTerminationRepository {
  private final JdbcTemplate jdbc;
  public record Receipt(String fingerprint, String result) {}

  public Optional<Receipt> findReceipt(String eventId) {
    return jdbc.query("SELECT fingerprint,result FROM matching_work_event_receipts WHERE event_id = ?",
        (rs, row) -> new Receipt(rs.getString(1), rs.getString(2)), eventId).stream().findFirst();
  }

  public Optional<String> findFact(long workId) {
    return jdbc.query("SELECT fingerprint FROM matching_work_terminations WHERE work_id = ?",
        (rs, row) -> rs.getString(1), workId).stream().findFirst();
  }

  public void recordFact(WorkTerminationEvent event) {
    jdbc.update("INSERT INTO matching_work_terminations(work_id,matching_id,fingerprint,event_type,occurred_at) VALUES (?,?,?,?,?)",
        event.workId(), event.matchingId(), event.factFingerprint(), event.eventType(), event.occurredAt());
  }

  public String recordReceipt(WorkTerminationEvent event, String result) {
    jdbc.update("INSERT INTO matching_work_event_receipts(event_id,work_id,matching_id,fingerprint,result,received_at)"
        + " VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(6))", event.eventId(), event.workId(), event.matchingId(), event.fingerprint(), result);
    return result;
  }
}
