package com.workernotfound.work.domain.work.policy;

import jakarta.validation.constraints.*;
import java.time.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "work.attendance")
public record AttendanceProperties(double radiusMeters, @NotNull Duration earlyWindow,
    @NotNull Duration lateWindow, @NotNull ZoneId timeZone, boolean allowEarlyCompletion, @NotNull CompletionRole completionRole) {
  @AssertTrue(message = "출근 인정 반경은 유한한 양수여야 합니다.")
  public boolean isRadiusValid() { return Double.isFinite(radiusMeters) && radiusMeters > 0; }

  @AssertTrue(message = "출근 허용 시간은 0 이상이어야 합니다.")
  public boolean isWindowValid() {
    return (earlyWindow == null || !earlyWindow.isNegative())
        && (lateWindow == null || !lateWindow.isNegative());
  }
}
