package com.workernotfound.payment.domain.refund.dto.response;

import com.workernotfound.payment.domain.refund.entity.PaymentRefund;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RefundResponse(
    String refundId,
    Long jobPostId,
    Long ownerMemberId,
    String orderId,
    BigDecimal amount,
    String currency,
    String reason,
    String status,
    LocalDateTime requestedAt,
    LocalDateTime completedAt,
    boolean reviewRequired,
    String failureCode) {
  public static RefundResponse from(PaymentRefund refund) {
    return new RefundResponse(refund.getId().toString(), refund.getJobPostId(),
        refund.getOwnerMemberId(), refund.getOrderId(), refund.getAmount(), refund.getCurrency(),
        refund.getReason(), refund.getStatus().name(), refund.getRequestedAt(),
        refund.getCompletedAt(), refund.isReviewRequired(), refund.getFailureCode());
  }
}
