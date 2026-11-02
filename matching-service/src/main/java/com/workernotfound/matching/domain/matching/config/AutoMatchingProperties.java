package com.workernotfound.matching.domain.matching.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 자동·폴백 매칭 임시 정책. 응답 제한시간은 제안 시각부터 세고, 근무 시작 전 여유 시간보다 늦게 끝나지 않게 줄인다.
 * 줄인 제한시간이 남지 않으면 새 제안을 하지 않는다.
 */
@Validated
@ConfigurationProperties(prefix = "matching.auto-matching")
public record AutoMatchingProperties(
	@NotNull Duration responseTimeout,
	@NotNull Duration workStartMargin,
	@Min(1) @Max(500) int batchSize
) {

	@AssertTrue(message = "자동 매칭 응답 제한시간은 1분 이상 1일 이하이고 근무 시작 여유 시간은 0 이상 1일 이하여야 합니다.")
	public boolean isTimingValid() {
		return responseTimeout != null && responseTimeout.compareTo(Duration.ofMinutes(1)) >= 0
			&& responseTimeout.compareTo(Duration.ofDays(1)) <= 0
			&& workStartMargin != null && !workStartMargin.isNegative()
			&& workStartMargin.compareTo(Duration.ofDays(1)) <= 0;
	}
}
