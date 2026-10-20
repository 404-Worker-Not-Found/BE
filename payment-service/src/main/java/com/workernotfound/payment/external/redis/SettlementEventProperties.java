package com.workernotfound.payment.external.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 정산 이벤트 Stream 소비 설정. MatchConfirmed는 matching Stream, WorkCompleted는 work Stream에서 읽는다.
 *
 * <p>소비자 사용 여부는 {@code payment.settlement-events.enabled}로 함께 켜고 끈다. 첫 실행 지연은 스케줄러가 직접 읽는다.
 */
@ConfigurationProperties(prefix = "payment.settlement-events")
public record SettlementEventProperties(String matchingStream, String workStream, int batchSize,
    Duration pollInterval) {
  public SettlementEventProperties {
    if (matchingStream == null || matchingStream.isBlank() || workStream == null || workStream.isBlank())
      throw new IllegalArgumentException("정산 이벤트 Stream 이름은 비어 있을 수 없습니다.");
    if (batchSize <= 0) throw new IllegalArgumentException("정산 이벤트 batch-size는 0보다 커야 합니다.");
    if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative())
      throw new IllegalArgumentException("정산 이벤트 poll-interval은 0보다 커야 합니다.");
  }
}
