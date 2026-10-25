package com.workernotfound.payment.external.toss;

import java.math.BigDecimal;

public interface TossGateway {
  TossPayment confirm(String orderId, String paymentKey, BigDecimal amount);

  TossPayment getPayment(String paymentKey);

  // 같은 idempotencyKey의 재요청은 토스가 15일 동안 처음 응답을 돌려준다.
  TossPayment cancel(String paymentKey, BigDecimal amount, String reason, String idempotencyKey);
}
