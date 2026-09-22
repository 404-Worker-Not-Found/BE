package com.workernotfound.payment.domain.order.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateOrderRequest(
    @NotNull @Positive Long jobPostId,
    @NotNull @PositiveOrZero Long jobVersion,
    @NotNull @Positive Long ownerMemberId,
    @NotNull @DecimalMin("100") @Digits(integer = 17, fraction = 0) BigDecimal amount,
    @NotNull @Pattern(regexp = "KRW") String currency) {
  public String fingerprint() {
    return jobPostId
        + ":"
        + jobVersion
        + ":"
        + ownerMemberId
        + ":"
        + amount.stripTrailingZeros().toPlainString()
        + ":"
        + currency;
  }
}
