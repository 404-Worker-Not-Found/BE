package com.workernotfound.payment.domain.payment.dto.response;

public record PaymentLockResponse(String paymentId) {
  public static PaymentLockResponse from(Long id) {
    return new PaymentLockResponse(id.toString());
  }
}
