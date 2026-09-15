package com.workernotfound.work.domain.work.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WorkCommandRepository {
  private final JdbcTemplate jdbc;

  public record Command(String fingerprint, Long workId) {}

  public Command lockCommand(String key, String fingerprint) {
    jdbc.update(
        "INSERT INTO work_commands (command_key, fingerprint) VALUES (?, ?) ON DUPLICATE KEY UPDATE"
            + " command_key = command_key",
        key,
        fingerprint);
    return jdbc.queryForObject(
        "SELECT fingerprint, work_id FROM work_commands WHERE command_key = ? FOR UPDATE",
        (rs, row) -> new Command(rs.getString(1), rs.getObject(2, Long.class)),
        key);
  }

  public void complete(String key, Long workId) {
    jdbc.update("UPDATE work_commands SET work_id = ? WHERE command_key = ?", workId, key);
  }

  public Long lockMatching(Long matchingId) {
    jdbc.update(
        "INSERT INTO work_matching_slots (matching_id) VALUES (?) ON DUPLICATE KEY UPDATE"
            + " matching_id = matching_id",
        matchingId);
    return jdbc.queryForObject(
        "SELECT active_work_id FROM work_matching_slots WHERE matching_id = ? FOR UPDATE",
        Long.class,
        matchingId);
  }

  public void activate(Long matchingId, Long workId) {
    jdbc.update(
        "UPDATE work_matching_slots SET active_work_id = ? WHERE matching_id = ?",
        workId,
        matchingId);
  }

  public void release(Long matchingId, Long workId) {
    jdbc.update(
        "UPDATE work_matching_slots SET active_work_id = NULL WHERE matching_id = ? AND"
            + " active_work_id = ?",
        matchingId,
        workId);
  }

  public void history(Long workId, String previous, String next, String commandKey) {
    jdbc.update(
        "INSERT INTO work_status_histories (work_id, previous_status, next_status, command_key,"
            + " occurred_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
        workId,
        previous,
        next,
        commandKey);
  }
}
