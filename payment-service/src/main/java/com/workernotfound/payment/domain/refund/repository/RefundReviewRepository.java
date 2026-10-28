package com.workernotfound.payment.domain.refund.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 자동 실행을 멈춘(검토 표시된) 환불의 운영자 조회와 수동 재개. 실행 임대·시도 정보는 JDBC로만 관리하므로 같은 방식으로 다룬다.
 *
 * <p>재개의 잠금 순서는 예치 → 재개 명령 → 환불이다. 결과 저장(주문 → 예치 → 환불)과 예치·환불 순서가 같아 교착이 생기지 않는다.
 * 재개 명령 저장은 FK 검사로 환불 행을 공유 잠금하므로 예치보다 먼저 하지 않는다.
 */
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

  public record Command(String fingerprint, String resumedReason, String rejection) {
    public boolean isResolved() {
      return resumedReason != null || rejection != null;
    }
  }

  public record RefundState(String status, boolean isReviewRequired, String reviewReason) {
    public boolean isUnderReview() {
      return "REQUESTED".equals(status) && isReviewRequired;
    }
  }

  // 환불의 예치 ID는 바뀌지 않으므로 잠그지 않고 읽는다.
  public Optional<Long> findDepositId(Long refundId) {
    return jdbc.queryForList("SELECT deposit_id FROM payment_refunds WHERE id = ?", Long.class, refundId)
        .stream()
        .findFirst();
  }

  public Command lockCommand(String key, String fingerprint, Long refundId, String operatorId, String note) {
    jdbc.update(
        "INSERT INTO payment_refund_review_resumptions (command_key, fingerprint, refund_id, operator_id, note,"
            + " requested_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE"
            + " command_key = command_key",
        key,
        fingerprint,
        refundId,
        operatorId,
        note);
    return jdbc.queryForObject(
        "SELECT fingerprint, resumed_reason, rejection FROM payment_refund_review_resumptions"
            + " WHERE command_key = ? FOR UPDATE",
        (rs, row) -> new Command(rs.getString(1), rs.getString(2), rs.getString(3)),
        key);
  }

  public boolean lockFundingBlocked(Long depositId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT funding_blocked FROM payment_deposits WHERE id = ? FOR UPDATE", Boolean.class, depositId));
  }

  public RefundState lockRefund(Long refundId) {
    return jdbc.queryForObject(
        "SELECT status, review_required, failure_code FROM payment_refunds WHERE id = ? FOR UPDATE",
        (rs, row) -> new RefundState(rs.getString(1), rs.getBoolean(2), rs.getString(3)),
        refundId);
  }

  // 검토 표시를 지우고 바로 실행 대상으로 돌린다. 시도 횟수는 유지해 다음 실행이 결제 조회부터 하게 하고,
  // 멱등 키 유효 기간은 재개 뒤 첫 점유 시각부터 다시 센다.
  public void resume(Long refundId) {
    jdbc.update(
        "UPDATE payment_refunds SET review_required = FALSE, failure_code = NULL, first_attempted_at = NULL,"
            + " next_attempt_at = CURRENT_TIMESTAMP(6), lease_token = NULL, lease_until = NULL,"
            + " version = version + 1 WHERE id = ?",
        refundId);
  }

  public void completeCommand(String key, String resumedReason) {
    jdbc.update(
        "UPDATE payment_refund_review_resumptions SET resumed_reason = ? WHERE command_key = ?",
        resumedReason,
        key);
  }

  public void rejectCommand(String key, String rejection) {
    jdbc.update(
        "UPDATE payment_refund_review_resumptions SET rejection = ? WHERE command_key = ?", rejection, key);
  }

  public void history(Long refundId, String reason) {
    jdbc.update(
        "INSERT INTO payment_refund_histories (refund_id, previous_status, next_status, reason, occurred_at)"
            + " VALUES (?, 'REQUESTED', 'REQUESTED', ?, CURRENT_TIMESTAMP(6))",
        refundId,
        reason);
  }
}
