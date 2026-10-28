package com.workernotfound.payment.domain.refund.service;

// reviewReason은 운영자가 확인한 검토 사유다. 그 사이 사유가 바뀌었으면 재개하지 않는다.
public record RefundReviewResumeCommand(String operatorId, String reviewReason, String note) {
  // 운영자 ID와 검토 사유에는 콜론이 없으므로 메모만 마지막에 두면 값 경계가 모호하지 않다.
  public String fingerprintPayload(Long refundId) {
    return "REFUND_REVIEW_RESUME:v1:" + refundId + ":" + reviewReason + ":" + operatorId + ":" + note;
  }
}
