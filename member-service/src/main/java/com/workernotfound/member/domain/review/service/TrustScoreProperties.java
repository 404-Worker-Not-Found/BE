package com.workernotfound.member.domain.review.service;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 신뢰 점수 임시 정책. 받은 평점 평균을 기본 평점 쪽으로 보정한 뒤 0~100으로 바꾸고 노쇼 횟수만큼 감점한다.
 * 기본 평점은 리뷰가 적은 회원의 점수가 리뷰 한두 개로 극단값이 되지 않게 하는 기준값이며, 리뷰 없이 노쇼만 있는 회원의
 * 평점 점수다. 보정 리뷰 수는 그 기준값을 리뷰 몇 개만큼의 무게로 섞을지 정한다. 노쇼 감점은 노쇼 1회당 빼는 점수다.
 */
@ConfigurationProperties(prefix = "member.trust-score")
public record TrustScoreProperties(BigDecimal priorRating, int priorReviewCount, BigDecimal noShowPenalty) {
	private static final BigDecimal MIN_RATING = BigDecimal.ONE;
	private static final BigDecimal MAX_RATING = BigDecimal.valueOf(5);
	private static final BigDecimal MAX_PENALTY = BigDecimal.valueOf(100);

	public TrustScoreProperties {
		if (priorRating == null || priorRating.compareTo(MIN_RATING) < 0 || priorRating.compareTo(MAX_RATING) > 0)
			throw new IllegalArgumentException("신뢰 점수 기본 평점은 1 이상 5 이하여야 합니다.");
		if (priorReviewCount < 0 || priorReviewCount > 1_000)
			throw new IllegalArgumentException("신뢰 점수 보정 리뷰 수는 0 이상 1000 이하여야 합니다.");
		if (!isValidPenalty(noShowPenalty))
			throw new IllegalArgumentException("신뢰 점수 노쇼 감점은 0 이상 100 이하이고 소수 둘째 자리까지여야 합니다.");
	}

	private static boolean isValidPenalty(BigDecimal penalty) {
		return penalty != null && penalty.signum() >= 0 && penalty.compareTo(MAX_PENALTY) <= 0
			&& penalty.stripTrailingZeros().scale() <= 2;
	}
}
