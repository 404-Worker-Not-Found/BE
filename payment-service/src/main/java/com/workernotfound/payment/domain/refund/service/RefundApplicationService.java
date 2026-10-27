package com.workernotfound.payment.domain.refund.service;

import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.domain.refund.dto.request.RefundRequest;
import com.workernotfound.payment.domain.refund.dto.response.RefundResponse;
import com.workernotfound.payment.domain.refund.repository.PaymentRefundRepository;
import com.workernotfound.payment.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefundApplicationService {
  private final RefundCommandService commands;
  private final PaymentRefundRepository refunds;

  // 같은 명령의 재요청은 처음 만든 환불의 현재 상태를 돌려준다.
  public RefundResponse request(String key, RefundRequest request) {
    var outcome = commands.request(key, new RefundCommand(request.jobPostId(), request.ownerMemberId(),
        request.amount(), request.currency(), request.reason()));
    if (outcome.rejection() != null) throw new BusinessException(outcome.rejection());
    return getRefund(outcome.refundId());
  }

  @Transactional(readOnly = true)
  public RefundResponse getRefund(Long refundId) {
    return refunds.findById(refundId).map(RefundResponse::from)
        .orElseThrow(() -> new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));
  }
}
