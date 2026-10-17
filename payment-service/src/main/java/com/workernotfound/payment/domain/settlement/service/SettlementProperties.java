package com.workernotfound.payment.domain.settlement.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 정산 설정. payoutDelay는 근무 완료 후 자동 지급 요청이 가능해질 때까지의 대기 기간이다.
 */
@ConfigurationProperties(prefix = "payment.settlement")
public record SettlementProperties(Duration payoutDelay) {
  public SettlementProperties {
    if (payoutDelay == null || payoutDelay.isNegative())
      throw new IllegalArgumentException("정산 지급 대기 기간은 0 이상이어야 합니다.");
  }
}
