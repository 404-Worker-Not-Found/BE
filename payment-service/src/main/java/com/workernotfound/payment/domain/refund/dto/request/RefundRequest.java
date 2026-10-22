package com.workernotfound.payment.domain.refund.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

// 환불 금액은 1원 이상 정수 KRW다. 100과 100.00은 같은 요청이다.
public record RefundRequest(
    @NotNull @Positive Long jobPostId,
    @NotNull @Positive Long ownerMemberId,
    @NotNull @DecimalMin("1") BigDecimal amount,
    @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
    @NotBlank @Size(max = 200) String reason) {
  // @Digits는 후행 0도 소수 자릿수로 세므로 100.00을 거절한다. 후행 0을 뺀 값으로 확인한다.
  @JsonIgnore
  @AssertTrue(message = "금액은 17자리 이하 정수여야 합니다.")
  public boolean isWholeAmount() {
    if (amount == null) return true;
    BigDecimal value = amount.stripTrailingZeros();
    return value.scale() <= 0 && value.precision() - value.scale() <= 17;
  }

  // 사유는 콜론을 포함할 수 있으므로 마지막에 둔다. 앞 값들에는 콜론이 없다.
  public String fingerprintPayload() {
    return "REFUND:v1:" + jobPostId + ":" + ownerMemberId + ":"
        + amount.stripTrailingZeros().toPlainString() + ":" + currency + ":" + reason;
  }
}
