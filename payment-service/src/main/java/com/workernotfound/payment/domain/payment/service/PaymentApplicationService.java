package com.workernotfound.payment.domain.payment.service;

import com.workernotfound.payment.domain.payment.dto.request.PaymentLockRequest;
import com.workernotfound.payment.domain.payment.dto.response.PaymentLockResponse;
import com.workernotfound.payment.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentApplicationService {
  private final PaymentCommandService commands;

  public PaymentLockResponse lock(String key, PaymentLockRequest request) {
    var outcome = commands.lock(key, request);
    requireSuccess(outcome);
    return PaymentLockResponse.from(outcome.paymentId());
  }

  public void release(String key, Long paymentId) {
    requireSuccess(commands.release(key, paymentId));
  }

  private void requireSuccess(PaymentCommandService.Outcome outcome) {
    if (outcome.rejection() != null) throw new BusinessException(outcome.rejection());
  }
}
