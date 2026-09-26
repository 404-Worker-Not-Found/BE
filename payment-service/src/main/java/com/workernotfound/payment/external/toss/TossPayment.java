package com.workernotfound.payment.external.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPayment(
    String paymentKey,
    String orderId,
    String status,
    String currency,
    String method,
    BigDecimal totalAmount,
    BigDecimal balanceAmount,
    OffsetDateTime approvedAt) {}
