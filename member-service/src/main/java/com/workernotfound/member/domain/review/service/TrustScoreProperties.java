package com.workernotfound.member.domain.review.service;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 신뢰 점수 임시 정책. 받은 평점 평균을 기본 평점 쪽으로 보정한 뒤 0~100으로 바꾼다.
 * 기본 평점은 리뷰가 적은 회원의 점수가 리뷰 한두 개로 극단값이 되지 않게 하는 기준값이고,
 * 보정 리뷰 수는 그 기준값을 리뷰 몇 개만큼의 무게로 섞을지 정한다.
 */
@ConfigurationProperties(prefix = "member.trust-score")
public record TrustScoreProperties(BigDecimal priorRating, int priorReviewCount) {
	private static final BigDecimal MIN_RATING = BigDecimal.ONE;
	private static final BigDecimal MAX_RATING = BigDecimal.valueOf(5);

	public TrustScoreProperties {
		if (priorRating == null || priorRating.compareTo(MIN_RATING) < 0 || priorRating.compareTo(MAX_RATING) > 0)
			throw new IllegalArgumentException("신뢰 점수 기본 평점은 1 이상 5 이하여야 합니다.");
		if (priorReviewCount < 0 || priorReviewCount > 1_000)
			throw new IllegalArgumentException("신뢰 점수 보정 리뷰 수는 0 이상 1000 이하여야 합니다.");
	}
}
