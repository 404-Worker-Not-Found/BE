package com.workernotfound.work.domain.outbox;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "work.outbox")
public record WorkOutboxProperties(@NotBlank String stream, @Min(1) @Max(500) int batchSize,
    @NotNull Duration leaseDuration, @NotNull Duration retryBaseDelay, @NotNull Duration retryMaxDelay) {
  @AssertTrue(message = "Outbox 임대와 재시도 시간은 양수이고 최대 대기는 기본 대기 이상이어야 합니다.")
  public boolean isTimingValid() {
    return leaseDuration != null && leaseDuration.compareTo(Duration.ofMillis(1)) >= 0
        && retryBaseDelay != null && retryBaseDelay.compareTo(Duration.ofMillis(1)) >= 0
        && retryMaxDelay != null && retryMaxDelay.compareTo(retryBaseDelay) >= 0
        && retryMaxDelay.compareTo(Duration.ofDays(1)) <= 0
        && leaseDuration.compareTo(Duration.ofDays(1)) <= 0;
  }
}
