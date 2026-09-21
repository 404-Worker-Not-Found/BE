package com.workernotfound.payment.domain.payment.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record PaymentLockRequest(
    @NotNull @Positive Long matchingId,
    @NotNull @Positive Long jobPostId,
    @NotNull @Positive Long ownerMemberId,
    @NotNull @Positive Long workerMemberId,
    @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
    @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
  public String fingerprintPayload() {
    return "LOCK:v1:" + matchingId + ":" + jobPostId + ":" + ownerMemberId + ":"
        + workerMemberId + ":" + amount.stripTrailingZeros().toPlainString() + ":" + currency;
  }
}
