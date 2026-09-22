package com.workernotfound.payment.domain.order.repository;

import com.workernotfound.payment.domain.order.dto.CreateOrderRequest;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PaymentOrderRepository {
  private final JdbcTemplate jdbc;

  public record Order(
      String id,
      Long jobId,
      Long jobVersion,
      Long ownerId,
      BigDecimal amount,
      String currency,
      String status,
      String paymentKey,
      String leaseToken,
      LocalDateTime leaseUntil,
      long reconcileRevision,
      int attempts) {}

  public record Command(String fingerprint, String orderId) {}

  public Command lockCommand(String key, String fingerprint) {
    jdbc.update(
        "INSERT INTO payment_order_commands(command_key,fingerprint) VALUES (?,?) ON DUPLICATE KEY"
            + " UPDATE command_key=command_key",
        key,
        fingerprint);
    return jdbc.queryForObject(
        "SELECT fingerprint,order_id FROM payment_order_commands WHERE command_key=? FOR UPDATE",
        (rs, n) -> new Command(rs.getString(1), rs.getString(2)),
        key);
  }

  public String lockJob(Long jobId) {
    jdbc.update(
        "INSERT INTO payment_order_jobs(job_post_id) VALUES (?) ON DUPLICATE KEY UPDATE"
            + " job_post_id=job_post_id",
        jobId);
    return jdbc.queryForObject(
        "SELECT active_order_id FROM payment_order_jobs WHERE job_post_id=? FOR UPDATE",
        String.class,
        jobId);
  }

  public void insert(String id, String key, CreateOrderRequest r) {
    jdbc.update(
        "INSERT INTO"
            + " payment_orders(id,job_post_id,job_version,owner_member_id,amount,currency,status)"
            + " VALUES (?,?,?,?,?,?,'READY')",
        id,
        r.jobPostId(),
        r.jobVersion(),
        r.ownerMemberId(),
        r.amount(),
        r.currency());
    jdbc.update(
        "UPDATE payment_order_jobs SET active_order_id=? WHERE job_post_id=?", id, r.jobPostId());
    jdbc.update("UPDATE payment_order_commands SET order_id=? WHERE command_key=?", id, key);
    history(id, "READY");
  }

  public Optional<Order> findById(String id) {
    return query(id, false);
  }

  public Optional<Order> findByIdForUpdate(String id) {
    return query(id, true);
  }

  private Optional<Order> query(String id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM payment_orders WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::map, id)
        .stream()
        .findFirst();
  }

  private Order map(ResultSet rs, int row) throws SQLException {
    return new Order(
        rs.getString("id"),
        rs.getLong("job_post_id"),
        rs.getLong("job_version"),
        rs.getLong("owner_member_id"),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getString("payment_key"),
        rs.getString("lease_token"),
        rs.getObject("lease_until", LocalDateTime.class),
        rs.getLong("reconcile_revision"),
        rs.getInt("attempts"));
  }

  public void transition(String id, String state) {
    jdbc.update("UPDATE payment_orders SET status=? WHERE id=?", state, id);
    history(id, state);
  }

  public void history(String id, String state) {
    jdbc.update("INSERT INTO payment_order_histories(order_id,status) VALUES (?,?)", id, state);
  }

  public void bind(String id, String paymentKey) {
    jdbc.update(
        "UPDATE payment_orders SET payment_key=?,next_check_at=CURRENT_TIMESTAMP(6) WHERE id=?",
        paymentKey,
        id);
    transition(id, "CONFIRMING");
  }

  public void claim(String id, String token) {
    jdbc.update(
        "UPDATE payment_orders SET lease_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL"
            + " 2 MINUTE) WHERE id=?",
        token,
        id);
  }

  public boolean activeLease(Order order) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT lease_until>CURRENT_TIMESTAMP(6) FROM payment_orders WHERE id=?",
            Boolean.class,
            order.id()));
  }

  public void release(Order order, boolean retry) {
    int seconds = Math.min(300, 30 * (1 << Math.min(order.attempts(), 3)));
    jdbc.update(
        "UPDATE payment_orders SET lease_token=NULL,lease_until=NULL,attempts=?,next_check_at=CASE"
            + " WHEN reconcile_revision<>? THEN CURRENT_TIMESTAMP(6) WHEN ? THEN"
            + " DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL ? SECOND) ELSE NULL END WHERE id=?",
        retry ? order.attempts() + 1 : 0,
        order.reconcileRevision(),
        retry,
        seconds,
        order.id());
  }

  public void queueHint(String id, String key) {
    jdbc.update(
        "UPDATE payment_orders SET"
            + " reconcile_revision=reconcile_revision+1,next_check_at=COALESCE(next_check_at,CURRENT_TIMESTAMP(6))"
            + " WHERE id=? AND payment_key=? AND status IN ('CONFIRMING','DEPOSITED')",
        id,
        key);
  }

  public List<String> findDueOrders() {
    return jdbc.queryForList(
        "SELECT id FROM payment_orders WHERE next_check_at<=CURRENT_TIMESTAMP(6) AND (lease_until"
            + " IS NULL OR lease_until<=CURRENT_TIMESTAMP(6)) ORDER BY next_check_at,id LIMIT 20",
        String.class);
  }

  public void credit(Order order, LocalDateTime approvedAt) {
    jdbc.update(
        "INSERT INTO"
            + " payment_deposits(job_post_id,owner_member_id,currency,deposited_amount,locked_amount,version)"
            + " VALUES (?,?,?,?,0,0)",
        order.jobId(),
        order.ownerId(),
        order.currency(),
        order.amount());
    jdbc.update("UPDATE payment_orders SET approved_at=? WHERE id=?", approvedAt, order.id());
    transition(order.id(), "DEPOSITED");
    notifyFunding(order.id(), true);
  }

  public void block(Order order) {
    jdbc.update(
        "UPDATE payment_deposits SET funding_blocked=TRUE,version=version+1 WHERE job_post_id=?",
        order.jobId());
    transition(order.id(), "REVIEW_REQUIRED");
    notifyFunding(order.id(), false);
  }

  private void notifyFunding(String id, boolean funded) {
    jdbc.update(
        "INSERT INTO payment_funding_notifications(order_id,revision,funded) VALUES (?,1,?) ON"
            + " DUPLICATE KEY UPDATE"
            + " revision=revision+1,funded=?,delivered=FALSE,next_attempt_at=CURRENT_TIMESTAMP(6),lease_token=NULL,lease_until=NULL,attempts=0",
        id,
        funded,
        funded);
  }
}
