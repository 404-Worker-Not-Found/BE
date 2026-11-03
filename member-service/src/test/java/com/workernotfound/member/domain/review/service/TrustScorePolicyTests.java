package com.workernotfound.member.domain.review.service;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 받은 평점 평균을 기본 평점(3.0) 쪽으로 리뷰 3개 무게만큼 보정한 뒤 0~100으로 바꾼다.
class TrustScorePolicyTests {
	private final TrustScorePolicy policy = new TrustScorePolicy(new TrustScoreProperties(new BigDecimal("3.0"), 3));

	@ParameterizedTest
	@CsvSource({
		"1, 5, 62.50",   // (9 + 5) / 4 = 3.5
		"1, 1, 37.50",   // (9 + 1) / 4 = 2.5
		"3, 15, 75.00",  // (9 + 15) / 6 = 4.0
		"2, 9, 65.00",   // (9 + 9) / 5 = 3.6
		"3, 13, 66.67",  // (9 + 13) / 6 = 3.666...
		"100, 500, 98.54",
		"100, 100, 1.46"
	})
	void shrinksAverageRatingTowardPriorAndScalesToHundred(long reviewCount, long ratingSum, String expected) {
		assertThat(policy.calculate(reviewCount, ratingSum)).isEqualByComparingTo(expected);
	}

	@Test
	void withoutPriorWeightUsesPlainAverage() {
		TrustScorePolicy plain = new TrustScorePolicy(new TrustScoreProperties(new BigDecimal("3.0"), 0));

		assertThat(plain.calculate(1, 5)).isEqualByComparingTo("100.00");
		assertThat(plain.calculate(2, 2)).isEqualByComparingTo("0.00");
	}

	@ParameterizedTest
	@CsvSource({"0, 0", "1, 0", "1, 6", "-1, 3"})
	void rejectsImpossibleRatingTotals(long reviewCount, long ratingSum) {
		assertThatThrownBy(() -> policy.calculate(reviewCount, ratingSum)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsOutOfRangePolicySettings() {
		assertThatThrownBy(() -> new TrustScoreProperties(new BigDecimal("0.9"), 3))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TrustScoreProperties(new BigDecimal("5.1"), 3))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TrustScoreProperties(new BigDecimal("3.0"), -1))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
