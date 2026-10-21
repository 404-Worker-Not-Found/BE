package com.workernotfound.payment.domain.refund.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

// 환불 금액은 1원 이상 정수 KRW다. 100과 100.00은 같은 요청이다.
public record RefundRequest(
    @NotNull @Positive Long jobPostId,
    @NotNull @Positive Long ownerMemberId,
    @NotNull @DecimalMin("1") @Digits(integer = 17, fraction = 0) BigDecimal amount,
    @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
    @NotBlank @Size(max = 200) String reason) {
  // 사유는 콜론을 포함할 수 있으므로 마지막에 둔다. 앞 값들에는 콜론이 없다.
  public String fingerprintPayload() {
    return "REFUND:v1:" + jobPostId + ":" + ownerMemberId + ":"
        + amount.stripTrailingZeros().toPlainString() + ":" + currency + ":" + reason;
  }
}
