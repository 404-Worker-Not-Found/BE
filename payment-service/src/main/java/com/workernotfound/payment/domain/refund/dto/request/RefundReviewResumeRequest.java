package com.workernotfound.payment.domain.refund.dto.request;

import jakarta.validation.constraints.*;

// reviewReason은 운영자가 확인한 검토 사유다. 그 사이 사유가 바뀌었으면 재개하지 않는다.
public record RefundReviewResumeRequest(
    @NotNull @Pattern(regexp = "[A-Za-z0-9._@-]{1,64}") String operatorId,
    @NotNull @Pattern(regexp = "[A-Z_]{1,64}") String reviewReason,
    @NotBlank @Size(max = 200) String note) {
  // 운영자 ID와 검토 사유에는 콜론이 없으므로 메모만 마지막에 두면 값 경계가 모호하지 않다.
  public String fingerprintPayload(Long refundId) {
    return "REFUND_REVIEW_RESUME:v1:" + refundId + ":" + reviewReason + ":" + operatorId + ":" + note;
  }
}
