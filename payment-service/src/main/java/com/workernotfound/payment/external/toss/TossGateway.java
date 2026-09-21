package com.workernotfound.payment.external.toss;

import java.math.BigDecimal;

public interface TossGateway {
  TossPayment confirm(String orderId, String paymentKey, BigDecimal amount);

  TossPayment getPayment(String paymentKey);
}
