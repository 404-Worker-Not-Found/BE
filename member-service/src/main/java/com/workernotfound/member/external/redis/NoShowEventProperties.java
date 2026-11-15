package com.workernotfound.member.external.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 신뢰 점수 노쇼 감점용 근무 이벤트 Stream 소비 설정. 소비자 사용 여부는 {@code member.no-show-events.enabled}로 켜고 끈다.
 * 첫 실행 지연은 스케줄러가 직접 읽는다.
 */
@ConfigurationProperties(prefix = "member.no-show-events")
public record NoShowEventProperties(String workStream, int batchSize, Duration pollInterval) {
	public NoShowEventProperties {
		if (workStream == null || workStream.isBlank())
			throw new IllegalArgumentException("노쇼 근무 이벤트 Stream 이름은 비어 있을 수 없습니다.");
		if (batchSize <= 0) throw new IllegalArgumentException("노쇼 근무 이벤트 batch-size는 0보다 커야 합니다.");
		if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative())
			throw new IllegalArgumentException("노쇼 근무 이벤트 poll-interval은 0보다 커야 합니다.");
	}
}
