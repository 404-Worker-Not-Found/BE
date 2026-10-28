package com.workernotfound.payment.domain.refund.dto.response;

import com.workernotfound.payment.domain.refund.repository.RefundReviewRepository.Review;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// reviewReason은 자동 실행을 멈춘 사유, resumable은 지금 재개 요청이 받아들여질 수 있는지다.
public record RefundReviewResponse(
    String refundId,
    Long jobPostId,
    Long ownerMemberId,
    String orderId,
    BigDecimal amount,
    String currency,
    String reason,
    String reviewReason,
    int attempts,
    LocalDateTime requestedAt,
    LocalDateTime firstAttemptedAt,
    boolean fundingBlocked,
    boolean resumable) {
  public static RefundReviewResponse of(Review review, boolean isResumable) {
    return new RefundReviewResponse(review.refundId().toString(), review.jobPostId(), review.ownerMemberId(),
        review.orderId(), review.amount(), review.currency(), review.reason(), review.reviewReason(),
        review.attempts(), review.requestedAt(), review.firstAttemptedAt(), review.isFundingBlocked(),
        isResumable);
  }
}
