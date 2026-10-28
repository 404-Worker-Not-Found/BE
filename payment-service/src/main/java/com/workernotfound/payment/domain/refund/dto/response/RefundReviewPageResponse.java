package com.workernotfound.payment.domain.refund.dto.response;

import java.util.List;

// hasNext 동안 nextAfterRefundId로 이어서 조회한다. 빈 응답은 입력 커서를 그대로 돌려준다.
public record RefundReviewPageResponse(
    List<RefundReviewResponse> reviews, Long nextAfterRefundId, boolean hasNext) {}
