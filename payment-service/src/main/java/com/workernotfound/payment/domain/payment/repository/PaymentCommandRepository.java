package com.workernotfound.payment.domain.payment.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentCommandRepository {
  private final JdbcTemplate jdbc;

  public record Command(String fingerprint, Long paymentId, String rejection) {}

  public Command lockCommand(String key, String fingerprint) {
    jdbc.update(
        "INSERT INTO payment_commands (command_key, fingerprint) VALUES (?, ?) ON DUPLICATE KEY UPDATE"
            + " command_key = command_key",
        key,
        fingerprint);
    return jdbc.queryForObject(
        "SELECT fingerprint, payment_id, rejection FROM payment_commands WHERE command_key = ? FOR UPDATE",
        (rs, row) -> new Command(rs.getString(1), rs.getObject(2, Long.class), rs.getString(3)),
        key);
  }

  public void reject(String key, String rejection) {
    jdbc.update("UPDATE payment_commands SET rejection = ? WHERE command_key = ?", rejection, key);
  }

  public void complete(String key, Long paymentId) {
    jdbc.update("UPDATE payment_commands SET payment_id = ? WHERE command_key = ?", paymentId, key);
  }

  public Long lockMatching(Long matchingId) {
    jdbc.update(
        "INSERT INTO payment_matching_slots (matching_id) VALUES (?) ON DUPLICATE KEY UPDATE"
            + " matching_id = matching_id",
        matchingId);
    return jdbc.queryForObject(
        "SELECT active_payment_id FROM payment_matching_slots WHERE matching_id = ? FOR UPDATE",
        Long.class,
        matchingId);
  }

  public void activate(Long matchingId, Long paymentId) {
    jdbc.update(
        "UPDATE payment_matching_slots SET active_payment_id = ? WHERE matching_id = ?",
        paymentId,
        matchingId);
  }

  public void release(Long matchingId, Long paymentId) {
    jdbc.update(
        "UPDATE payment_matching_slots SET active_payment_id = NULL WHERE matching_id = ? AND"
            + " active_payment_id = ?",
        matchingId,
        paymentId);
  }

  public void history(Long paymentId, String previous, String next, String commandKey) {
    jdbc.update(
        "INSERT INTO payment_status_histories (payment_id, previous_status, next_status, command_key,"
            + " occurred_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
        paymentId,
        previous,
        next,
        commandKey);
  }
}
