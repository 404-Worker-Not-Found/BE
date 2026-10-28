package com.workernotfound.payment.domain.refund.service;

import java.math.BigDecimal;

public record RefundCommand(
    Long jobPostId, Long ownerMemberId, BigDecimal amount, String currency, String reason) {
  // 사유는 콜론을 포함할 수 있으므로 마지막에 둔다. 기존 요청의 v1 지문 형식을 유지한다.
  public String fingerprintPayload() {
    return "REFUND:v1:" + jobPostId + ":" + ownerMemberId + ":"
        + amount.stripTrailingZeros().toPlainString() + ":" + currency + ":" + reason;
  }
}
