package com.workernotfound.payment.domain.refund.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 환불 PG 실행의 임대·재시도 상태. 주문 승인 복구와 같이 DB 시각으로 2분 임대를 잡고, 임대 토큰이 같은 작업자만 결과를 저장한다.
 */
@Repository
@RequiredArgsConstructor
public class RefundExecutionRepository {
  // 토스 멱등 키는 15일 유효하다. 하루 여유를 두고 그 뒤에는 같은 키로 다시 보내지 않는다.
  private static final int IDEMPOTENCY_WINDOW_DAYS = 14;
  private static final String CLAIMABLE =
      "SELECT r.id FROM payment_refunds r JOIN payment_deposits d ON d.id = r.deposit_id"
          + " WHERE r.status = 'REQUESTED' AND r.review_required = FALSE AND d.funding_blocked = FALSE"
          + " AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= CURRENT_TIMESTAMP(6))"
          + " AND (r.lease_until IS NULL OR r.lease_until <= CURRENT_TIMESTAMP(6))"
          // 한 주문의 환불은 먼저 요청된 것부터 하나씩 실행해 취소 합계로 결과를 판단할 수 있게 한다.
          + " AND NOT EXISTS (SELECT 1 FROM payment_refunds e WHERE e.order_id = r.order_id"
          + " AND e.status = 'REQUESTED' AND e.id < r.id)";
  private final JdbcTemplate jdbc;

  public record Claim(Long refundId, String orderId, String paymentKey, BigDecimal amount,
      String reason, String token, int attempt, boolean isPastIdempotencyWindow) {
    public String idempotencyKey() {
      return "refund-" + refundId;
    }
  }

  // refundId가 있으면 그 환불만, 없으면 가장 먼저 실행할 환불 하나를 점유한다. attempts는 PG 호출 전에 커밋된다.
  public Claim claim(Long refundId) {
    List<Long> ids = refundId == null
        ? jdbc.queryForList(CLAIMABLE + " ORDER BY r.next_attempt_at, r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED",
            Long.class)
        : jdbc.queryForList(CLAIMABLE + " AND r.id = ? FOR UPDATE OF r SKIP LOCKED", Long.class, refundId);
    if (ids.isEmpty()) return null;
    String token = UUID.randomUUID().toString();
    jdbc.update(
        "UPDATE payment_refunds SET lease_token = ?, lease_until = DATE_ADD(CURRENT_TIMESTAMP(6),"
            + " INTERVAL 2 MINUTE), attempts = attempts + 1, first_attempted_at = COALESCE(first_attempted_at,"
            + " CURRENT_TIMESTAMP(6)) WHERE id = ?",
        token,
        ids.get(0));
    return jdbc.queryForObject(
        "SELECT r.id, r.order_id, o.payment_key, r.amount, r.reason, r.attempts,"
            + " r.first_attempted_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL " + IDEMPOTENCY_WINDOW_DAYS
            + " DAY) FROM payment_refunds r JOIN payment_orders o ON o.id = r.order_id WHERE r.id = ?",
        (rs, row) -> new Claim(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
            rs.getString(5), token, rs.getInt(6), rs.getBoolean(7)),
        ids.get(0));
  }

  public boolean ownsLease(Claim claim) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT lease_token = ? AND lease_until > CURRENT_TIMESTAMP(6) FROM payment_refunds WHERE id = ?"
            + " FOR UPDATE",
        Boolean.class,
        claim.token(),
        claim.refundId()));
  }

  public void release(Claim claim) {
    jdbc.update(
        "UPDATE payment_refunds SET lease_token = NULL, lease_until = NULL WHERE id = ?", claim.refundId());
  }

  public void retryLater(Claim claim) {
    int seconds = Math.min(300, 30 * (1 << Math.min(claim.attempt() - 1, 3)));
    jdbc.update(
        "UPDATE payment_refunds SET lease_token = NULL, lease_until = NULL, next_attempt_at ="
            + " DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? SECOND) WHERE id = ?",
        seconds,
        claim.refundId());
  }

  // PG에 보냈을 수 있는(attempts > 0) 주문의 진행 중 환불. 주문당 하나만 실행하므로 가장 앞선 것 하나다.
  public Optional<BigDecimal> findInFlightAmount(String orderId) {
    return jdbc.queryForList(
            "SELECT amount FROM payment_refunds WHERE order_id = ? AND status = 'REQUESTED' AND"
                + " review_required = FALSE AND attempts > 0 ORDER BY id LIMIT 1",
            BigDecimal.class,
            orderId)
        .stream()
        .findFirst();
  }

  public void history(Long refundId, String previous, String next, String reason) {
    jdbc.update(
        "INSERT INTO payment_refund_histories (refund_id, previous_status, next_status, reason, occurred_at)"
            + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))",
        refundId,
        previous,
        next,
        reason);
  }
}
