package com.workernotfound.member.domain.review.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 리뷰 임시 정책. 작성 기간은 근무 완료 시각부터 센다. 평점 범위(1~5)와 코멘트 길이(500자)는 DB 제약과 함께 고정한다.
 */
@ConfigurationProperties(prefix = "member.review")
public record ReviewProperties(Duration writePeriod) {
	public ReviewProperties {
		if (writePeriod == null || writePeriod.isZero() || writePeriod.isNegative())
			throw new IllegalArgumentException("리뷰 작성 기간은 0보다 커야 합니다.");
	}
}
