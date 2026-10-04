package com.workernotfound.work.domain.outbox;

import com.workernotfound.work.domain.work.event.WorkLifecycleEvent;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@RequiredArgsConstructor
public class WorkOutboxRepository {
  private final JdbcTemplate jdbc;

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(WorkLifecycleEvent event, String payload) {
    jdbc.update("INSERT INTO work_outbox (event_id, work_id, event_type, revision, schema_version, "
        + "occurred_at, payload, next_attempt_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        event.eventId(), event.workId(), event.eventType(), event.revision(), event.version(),
        event.occurredAt(), payload, event.occurredAt());
  }

  public List<Long> candidates(LocalDateTime now, int limit) {
    return jdbc.queryForList("SELECT id FROM work_outbox WHERE published_at IS NULL AND next_attempt_at <= ? "
        + "AND (lease_until IS NULL OR lease_until <= ?) ORDER BY id LIMIT ?", Long.class, now, now, limit);
  }

  @Transactional
  public Optional<WorkOutboxMessage> claim(long id, String token, LocalDateTime now, LocalDateTime until) {
    int changed = jdbc.update("UPDATE work_outbox SET lease_token = ?, lease_until = ? "
        + "WHERE id = ? AND published_at IS NULL AND next_attempt_at <= ? "
        + "AND (lease_until IS NULL OR lease_until <= ?)", token, until, id, now, now);
    if (changed == 0) return Optional.empty();
    return jdbc.query("SELECT * FROM work_outbox WHERE id = ? AND lease_token = ?", (rs, row) ->
        new WorkOutboxMessage(rs.getLong("id"), rs.getString("event_id"), rs.getLong("work_id"),
            rs.getString("event_type"), rs.getLong("revision"), rs.getInt("schema_version"),
            rs.getTimestamp("occurred_at").toLocalDateTime(), rs.getString("payload"), rs.getInt("retry_count")),
        id, token).stream().findFirst();
  }

  public void published(long id, String token, LocalDateTime at) {
    jdbc.update("UPDATE work_outbox SET published_at = ?, lease_token = NULL, lease_until = NULL "
        + "WHERE id = ? AND lease_token = ? AND published_at IS NULL", at, id, token);
  }

  public void failed(long id, String token, LocalDateTime nextAttempt) {
    jdbc.update("UPDATE work_outbox SET retry_count = LEAST(retry_count + 1, 1000000), next_attempt_at = ?, "
        + "lease_token = NULL, lease_until = NULL WHERE id = ? AND lease_token = ? AND published_at IS NULL",
        nextAttempt, id, token);
  }
}
