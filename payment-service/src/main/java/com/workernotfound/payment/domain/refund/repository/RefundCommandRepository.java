package com.workernotfound.payment.domain.refund.repository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RefundCommandRepository {
  private final JdbcTemplate jdbc;

  public record Command(String fingerprint, Long refundId, String rejection) {}

  public Command lockCommand(String key, String fingerprint) {
    jdbc.update(
        "INSERT INTO payment_refund_commands (command_key, fingerprint) VALUES (?, ?) ON DUPLICATE KEY"
            + " UPDATE command_key = command_key",
        key,
        fingerprint);
    return jdbc.queryForObject(
        "SELECT fingerprint, refund_id, rejection FROM payment_refund_commands WHERE command_key = ?"
            + " FOR UPDATE",
        (rs, row) -> new Command(rs.getString(1), rs.getObject(2, Long.class), rs.getString(3)),
        key);
  }

  public void reject(String key, String rejection) {
    jdbc.update(
        "UPDATE payment_refund_commands SET rejection = ? WHERE command_key = ?", rejection, key);
  }

  public void complete(String key, Long refundId) {
    jdbc.update(
        "UPDATE payment_refund_commands SET refund_id = ? WHERE command_key = ?", refundId, key);
  }

  // 예치는 검증된 주문 승인으로만 생기고 공고당 하나라서 DEPOSITED 주문도 하나다. 주문 행은 잠그지 않는다.
  public List<String> findDepositedOrderIds(Long jobPostId) {
    return jdbc.queryForList(
        "SELECT id FROM payment_orders WHERE job_post_id = ? AND status = 'DEPOSITED'",
        String.class,
        jobPostId);
  }

  public void history(Long refundId, String previous, String next) {
    jdbc.update(
        "INSERT INTO payment_refund_histories (refund_id, previous_status, next_status, occurred_at)"
            + " VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))",
        refundId,
        previous,
        next);
  }
}
