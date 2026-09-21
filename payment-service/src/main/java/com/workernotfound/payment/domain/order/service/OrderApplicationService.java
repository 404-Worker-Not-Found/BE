package com.workernotfound.payment.domain.order.service;

import com.workernotfound.payment.domain.order.dto.*;
import com.workernotfound.payment.domain.order.repository.PaymentOrderRepository;
import com.workernotfound.payment.external.toss.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderApplicationService {
  private final OrderTransactionService transactions;
  private final PaymentOrderRepository orders;
  private final TossGateway toss;

  public OrderResponse create(String key, CreateOrderRequest request) {
    return transactions.create(key, request);
  }

  public OrderResponse getOrder(String id, Long ownerId) {
    return transactions.getOrder(id, ownerId);
  }

  public OrderResponse confirm(String id, Long ownerId, ConfirmOrderRequest request) {
    var claim = transactions.prepare(id, ownerId, request);
    if (claim != null) execute(claim);
    return transactions.getOrder(id, ownerId);
  }

  public void recover(String id) {
    var claim = transactions.claimRecovery(id);
    if (claim != null) execute(claim);
  }

  public void receiveWebhook(TossWebhook event) {
    if ("PAYMENT_STATUS_CHANGED".equals(event.eventType()))
      orders.queueHint(event.data().orderId(), event.data().paymentKey());
  }

  private void execute(OrderTransactionService.Claim claim) {
    var order = claim.order();
    try {
      TossPayment result =
          claim.firstAttempt()
              ? toss.confirm(order.id(), order.paymentKey(), order.amount())
              : toss.getPayment(order.paymentKey());
      transactions.verifyIdentity(order, result);
      if (!claim.firstAttempt()
          && "CONFIRMING".equals(order.status())
          && "IN_PROGRESS".equals(result.status()))
        result = toss.confirm(order.id(), order.paymentKey(), order.amount());
      transactions.finish(claim, result);
    } catch (RuntimeException exception) {
      try {
        transactions.retry(claim);
      } catch (RuntimeException retryFailure) {
        exception.addSuppressed(retryFailure);
      }
      throw exception;
    }
  }
}
