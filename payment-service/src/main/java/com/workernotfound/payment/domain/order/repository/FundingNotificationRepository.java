package com.workernotfound.payment.domain.order.repository;

import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class FundingNotificationRepository {
  private final JdbcTemplate jdbc;
  private final PaymentOrderRepository orders;

  public record Notification(
      String orderId,
      Long jobPostId,
      Long jobVersion,
      Long ownerMemberId,
      BigDecimal amount,
      String currency,
      long revision,
      boolean funded,
      String token,
      int attempts) {
    public String commandId() {
      return "funding-" + orderId + "-" + revision;
    }
  }

  @Transactional
  public Notification claim() {
    List<String> ids =
        jdbc.queryForList(
            "SELECT order_id FROM payment_funding_notifications WHERE delivered=FALSE AND"
                + " next_attempt_at<=CURRENT_TIMESTAMP(6) AND (lease_until IS NULL OR"
                + " lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY next_attempt_at,order_id LIMIT 1"
                + " FOR UPDATE SKIP LOCKED",
            String.class);
    if (ids.isEmpty()) return null;
    String id = ids.get(0), token = UUID.randomUUID().toString();
    jdbc.update(
        "UPDATE payment_funding_notifications SET"
            + " lease_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 2 MINUTE) WHERE"
            + " order_id=?",
        token,
        id);
    var order = orders.findById(id).orElseThrow();
    return jdbc.queryForObject(
        "SELECT revision,funded,attempts FROM payment_funding_notifications WHERE order_id=?",
        (rs, n) ->
            new Notification(
                id,
                order.jobId(),
                order.jobVersion(),
                order.ownerId(),
                order.amount(),
                order.currency(),
                rs.getLong(1),
                rs.getBoolean(2),
                token,
                rs.getInt(3)),
        id);
  }

  public void acknowledge(Notification notification) {
    jdbc.update(
        "UPDATE payment_funding_notifications SET delivered=TRUE,lease_token=NULL,lease_until=NULL"
            + " WHERE order_id=? AND revision=? AND lease_token=? AND"
            + " lease_until>CURRENT_TIMESTAMP(6)",
        notification.orderId(),
        notification.revision(),
        notification.token());
  }

  public void retry(Notification n) {
    int delay = Math.min(300, 30 * (1 << Math.min(n.attempts(), 3)));
    jdbc.update(
        "UPDATE payment_funding_notifications SET"
            + " attempts=attempts+1,lease_token=NULL,lease_until=NULL,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL"
            + " ? SECOND) WHERE order_id=? AND revision=? AND lease_token=?",
        delay,
        n.orderId(),
        n.revision(),
        n.token());
  }
}
