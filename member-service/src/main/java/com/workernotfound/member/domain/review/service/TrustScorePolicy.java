package com.workernotfound.member.domain.review.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 받은 리뷰 평점과 노쇼 횟수로 계산하는 신뢰 점수(임시 정책).
 *
 * <p>보정 평점 = (기본 평점 × 보정 리뷰 수 + 평점 합) / (보정 리뷰 수 + 리뷰 수), 평점 점수 = (보정 평점 - 1) / 4 × 100.
 * 받은 리뷰가 없으면 기본 평점을 평점 점수로 쓴다. 신뢰 점수 = max(0, 평점 점수 - 노쇼 1회당 감점 × 노쇼 횟수).
 * 평점 점수는 소수 둘째 자리에서 반올림한다. 리뷰와 노쇼가 모두 없으면 점수를 만들지 않는다. 감점의 회복·시간 감쇠는 없다.
 */
@Component
@RequiredArgsConstructor
public class TrustScorePolicy {
	public static final String VERSION = "review-rating-no-show-v1";

	private static final BigDecimal MIN_RATING = BigDecimal.ONE;
	private static final BigDecimal RATING_SPAN = BigDecimal.valueOf(4);
	private static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);
	private static final int MAX_RATING = 5;

	private final TrustScoreProperties properties;

	public BigDecimal calculate(long reviewCount, long ratingSum, long noShowCount) {
		if (!isValidInput(reviewCount, ratingSum, noShowCount))
			throw new IllegalArgumentException("신뢰 점수는 받은 리뷰나 노쇼가 1개 이상인 올바른 집계로만 계산합니다.");
		BigDecimal penalty = properties.noShowPenalty().multiply(BigDecimal.valueOf(noShowCount));
		return ratingScore(reviewCount, ratingSum).subtract(penalty).max(BigDecimal.ZERO)
			.setScale(2, RoundingMode.HALF_UP);
	}

	private static boolean isValidInput(long reviewCount, long ratingSum, long noShowCount) {
		boolean hasValidRatings = reviewCount >= 0 && ratingSum >= reviewCount && ratingSum <= reviewCount * MAX_RATING;
		return hasValidRatings && noShowCount >= 0 && (reviewCount > 0 || noShowCount > 0);
	}

	private BigDecimal ratingScore(long reviewCount, long ratingSum) {
		if (reviewCount == 0) return toScore(properties.priorRating());
		BigDecimal priorWeight = BigDecimal.valueOf(properties.priorReviewCount());
		BigDecimal adjustedRating = properties.priorRating().multiply(priorWeight).add(BigDecimal.valueOf(ratingSum))
			.divide(priorWeight.add(BigDecimal.valueOf(reviewCount)), MathContext.DECIMAL64);
		return toScore(adjustedRating);
	}

	private static BigDecimal toScore(BigDecimal rating) {
		return rating.subtract(MIN_RATING).multiply(MAX_SCORE).divide(RATING_SPAN, 2, RoundingMode.HALF_UP);
	}
}
