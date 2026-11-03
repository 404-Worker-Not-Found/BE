package com.workernotfound.member.domain.review.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 받은 리뷰만으로 계산하는 신뢰 점수(임시 정책). 노쇼·취소 감점은 패널티 정책 확정 전까지 넣지 않는다.
 *
 * <p>보정 평점 = (기본 평점 × 보정 리뷰 수 + 평점 합) / (보정 리뷰 수 + 리뷰 수), 신뢰 점수 = (보정 평점 - 1) / 4 × 100.
 * 소수 둘째 자리에서 반올림한다. 받은 리뷰가 없으면 점수를 만들지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TrustScorePolicy {
	public static final String VERSION = "review-rating-v1";

	private static final BigDecimal MIN_RATING = BigDecimal.ONE;
	private static final BigDecimal RATING_SPAN = BigDecimal.valueOf(4);
	private static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);

	private final TrustScoreProperties properties;

	public BigDecimal calculate(long reviewCount, long ratingSum) {
		if (reviewCount <= 0 || ratingSum < reviewCount || ratingSum > reviewCount * 5)
			throw new IllegalArgumentException("신뢰 점수는 받은 리뷰가 1개 이상인 올바른 평점 집계로만 계산합니다.");
		BigDecimal priorWeight = BigDecimal.valueOf(properties.priorReviewCount());
		BigDecimal adjustedRating = properties.priorRating().multiply(priorWeight).add(BigDecimal.valueOf(ratingSum))
			.divide(priorWeight.add(BigDecimal.valueOf(reviewCount)), MathContext.DECIMAL64);
		return adjustedRating.subtract(MIN_RATING).multiply(MAX_SCORE)
			.divide(RATING_SPAN, 2, RoundingMode.HALF_UP);
	}
}
