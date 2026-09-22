package com.workernotfound.payment.domain.order.dto;

import com.workernotfound.payment.domain.order.repository.PaymentOrderRepository.Order;
import java.math.BigDecimal;

public record OrderResponse(
    String orderId,
    Long jobPostId,
    Long jobVersion,
    BigDecimal amount,
    String currency,
    String status) {
  public static OrderResponse from(Order order) {
    return new OrderResponse(
        order.id(),
        order.jobId(),
        order.jobVersion(),
        order.amount(),
        order.currency(),
        order.status());
  }
}
