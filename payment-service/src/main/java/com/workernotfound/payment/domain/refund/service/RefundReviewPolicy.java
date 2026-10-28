package com.workernotfound.payment.domain.refund.service;

import java.util.Set;

/**
 * 검토 표시된 환불을 운영자가 같은 환불의 자동 실행으로 되돌릴 수 있는지 판단한다.
 *
 * <p>PG 응답 식별 불일치와 멱등 키 유효 기간 초과는 운영자가 설정·PG 상태를 확인한 뒤 재개할 수 있다. 재개 뒤 첫 실행은 항상 결제를
 * 먼저 조회하므로 이미 반영된 취소를 다시 보내지 않는다. 설명되지 않는 취소는 예치가 차단되어 장부부터 맞춰야 하므로 재개하지 않는다.
 */
final class RefundReviewPolicy {
  private static final Set<String> RESUMABLE_REASONS = Set.of("PROVIDER_MISMATCH", "IDEMPOTENCY_WINDOW_EXPIRED");

  private RefundReviewPolicy() {}

  static boolean isResumableReason(String reviewReason) {
    return reviewReason != null && RESUMABLE_REASONS.contains(reviewReason);
  }

  static boolean isResumable(String reviewReason, boolean isFundingBlocked) {
    return isResumableReason(reviewReason) && !isFundingBlocked;
  }
}
