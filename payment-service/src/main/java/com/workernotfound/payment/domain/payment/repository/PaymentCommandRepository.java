package com.workernotfound.payment.domain.payment.repository;

import java.util.List;
import java.util.Optional;
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

  // 정산은 매칭 slot을 새로 만들지 않는다. slot이 없거나 활성 잠금이 없으면 빈 값이다.
  public Optional<Long> findActivePaymentForUpdate(Long matchingId) {
    List<Long> paymentIds = jdbc.queryForList(
        "SELECT active_payment_id FROM payment_matching_slots WHERE matching_id = ? FOR UPDATE",
        Long.class,
        matchingId);
    return paymentIds.isEmpty() ? Optional.empty() : Optional.ofNullable(paymentIds.get(0));
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

  public void settlementHistory(Long paymentId, Long settlementId) {
    jdbc.update(
        "INSERT INTO payment_status_histories (payment_id, previous_status, next_status, settlement_id,"
            + " occurred_at) VALUES (?, 'LOCKED', 'TRANSFERRED', ?, CURRENT_TIMESTAMP(6))",
        paymentId,
        settlementId);
  }
}
