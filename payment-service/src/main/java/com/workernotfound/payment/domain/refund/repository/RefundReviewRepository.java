package com.workernotfound.payment.domain.refund.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 자동 실행을 멈춘(검토 표시된) 환불의 운영자 조회. 실행 임대·시도 정보는 JDBC로만 관리하므로 같은 방식으로 읽는다. */
@Repository
@RequiredArgsConstructor
public class RefundReviewRepository {
  private final JdbcTemplate jdbc;

  public record Review(Long refundId, Long jobPostId, Long ownerMemberId, String orderId, BigDecimal amount,
      String currency, String reason, String reviewReason, int attempts, LocalDateTime requestedAt,
      LocalDateTime firstAttemptedAt, boolean isFundingBlocked) {}

  // afterRefundId 다음부터 ID 오름차순. 잠그지 않는 조회라 재개 판단에는 쓰지 않는다.
  public List<Review> findReviews(Long afterRefundId, int limit) {
    return jdbc.query(
        "SELECT r.id, r.job_post_id, r.owner_member_id, r.order_id, r.amount, r.currency, r.reason,"
            + " r.failure_code, r.attempts, r.requested_at, r.first_attempted_at, d.funding_blocked"
            + " FROM payment_refunds r JOIN payment_deposits d ON d.id = r.deposit_id"
            + " WHERE r.status = 'REQUESTED' AND r.review_required = TRUE AND r.id > ? ORDER BY r.id LIMIT ?",
        (rs, row) -> new Review(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
            rs.getBigDecimal(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9),
            rs.getObject(10, LocalDateTime.class), rs.getObject(11, LocalDateTime.class), rs.getBoolean(12)),
        afterRefundId,
        limit);
  }
}
