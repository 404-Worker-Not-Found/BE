package com.workernotfound.payment.domain.refund.service;

import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.domain.refund.dto.request.RefundRequest;
import com.workernotfound.payment.domain.refund.dto.response.*;
import com.workernotfound.payment.domain.refund.repository.PaymentRefundRepository;
import com.workernotfound.payment.domain.refund.repository.RefundReviewRepository;
import com.workernotfound.payment.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefundApplicationService {
  private final RefundCommandService commands;
  private final PaymentRefundRepository refunds;
  private final RefundReviewRepository reviews;

  // 같은 명령의 재요청은 처음 만든 환불의 현재 상태를 돌려준다.
  public RefundResponse request(String key, RefundRequest request) {
    var outcome = commands.request(key, request);
    if (outcome.rejection() != null) throw new BusinessException(outcome.rejection());
    return getRefund(outcome.refundId());
  }

  @Transactional(readOnly = true)
  public RefundResponse getRefund(Long refundId) {
    return refunds.findById(refundId).map(RefundResponse::from)
        .orElseThrow(() -> new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));
  }

  // 운영자가 확인할 검토 대상 환불. resumable은 조회 시점 기준이며 재개 요청이 잠근 뒤 다시 판단한다.
  @Transactional(readOnly = true)
  public RefundReviewPageResponse getReviews(Long afterRefundId, int size) {
    var result = reviews.findReviews(afterRefundId, size + 1);
    var content = result.stream().limit(size).map(review -> RefundReviewResponse.of(review,
        RefundReviewPolicy.isResumable(review.reviewReason(), review.isFundingBlocked()))).toList();
    Long nextAfterRefundId = content.isEmpty()
        ? afterRefundId : Long.valueOf(content.get(content.size() - 1).refundId());
    return new RefundReviewPageResponse(content, nextAfterRefundId, result.size() > size);
  }
}
