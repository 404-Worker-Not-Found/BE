package com.workernotfound.notification.domain.notification.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationEventRepository {
  private final JdbcTemplate jdbc;

  public String record(String eventId, String fingerprint) {
    jdbc.update("INSERT INTO notification_events (event_id, fingerprint) VALUES (?, ?) "
        + "ON DUPLICATE KEY UPDATE event_id = event_id", eventId, fingerprint);
    return jdbc.queryForObject("SELECT fingerprint FROM notification_events WHERE event_id = ? FOR UPDATE",
        String.class, eventId);
  }
}
