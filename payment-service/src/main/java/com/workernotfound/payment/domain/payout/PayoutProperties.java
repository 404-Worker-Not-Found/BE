package com.workernotfound.payment.domain.payout;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.payout")
public record PayoutProperties(Duration leaseDuration, Duration preparationRetryDelay) {
  public PayoutProperties {
    if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative())
      throw new IllegalArgumentException("지급 임대 시간은 0보다 커야 합니다.");
    if (preparationRetryDelay == null || preparationRetryDelay.isZero() || preparationRetryDelay.isNegative())
      throw new IllegalArgumentException("지급 준비 재시도 간격은 0보다 커야 합니다.");
  }
}
