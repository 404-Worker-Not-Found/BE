package com.workernotfound.payment.external.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 토스 Payment 객체 중 필요한 필드. 취소 금액 합계는 {@code totalAmount - balanceAmount}이며 cancels에는 취소 거래마다 항목이 하나씩
 * 쌓인다. 취소 항목에는 이쪽 환불 ID가 없으므로 환불 대조는 금액 합계로 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPayment(
    String paymentKey,
    String orderId,
    String status,
    String currency,
    String method,
    BigDecimal totalAmount,
    BigDecimal balanceAmount,
    OffsetDateTime approvedAt,
    List<Cancel> cancels) {
  public TossPayment {
    cancels = cancels == null ? List.of() : List.copyOf(cancels);
  }

  public TossPayment(String paymentKey, String orderId, String status, String currency,
      String method, BigDecimal totalAmount, BigDecimal balanceAmount, OffsetDateTime approvedAt) {
    this(paymentKey, orderId, status, currency, method, totalAmount, balanceAmount, approvedAt, null);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Cancel(String transactionKey, BigDecimal cancelAmount, String cancelStatus,
      OffsetDateTime canceledAt) {}
}
