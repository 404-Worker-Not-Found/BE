package com.workernotfound.member.domain.review.service;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 받은 평점 평균을 기본 평점(3.0) 쪽으로 리뷰 3개 무게만큼 보정해 0~100으로 바꾼 뒤 노쇼 1회당 10점을 뺀다.
class TrustScorePolicyTests {
	private final TrustScorePolicy policy = new TrustScorePolicy(
		new TrustScoreProperties(new BigDecimal("3.0"), 3, BigDecimal.TEN));

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
		assertThat(policy.calculate(reviewCount, ratingSum, 0)).isEqualByComparingTo(expected);
	}

	@ParameterizedTest
	@CsvSource({
		"1, 5, 1, 52.50",
		"3, 13, 2, 46.67",
		"3, 15, 7, 5.00",
		"1, 1, 4, 0.00",   // 37.50 - 40 → 0
		"0, 0, 1, 40.00",  // 리뷰가 없으면 기본 평점 3.0 = 50점에서 감점
		"0, 0, 5, 0.00",
		"0, 0, 6, 0.00"
	})
	void subtractsFixedPenaltyPerNoShowWithoutGoingBelowZero(long reviewCount, long ratingSum, long noShowCount,
		String expected) {
		assertThat(policy.calculate(reviewCount, ratingSum, noShowCount)).isEqualByComparingTo(expected);
	}

	@Test
	void penaltyAndPriorAreConfigurable() {
		TrustScorePolicy custom = new TrustScorePolicy(new TrustScoreProperties(new BigDecimal("4.0"), 0,
			new BigDecimal("2.5")));

		assertThat(custom.calculate(0, 0, 3)).isEqualByComparingTo("67.50"); // 75 - 7.5
		assertThat(custom.calculate(1, 5, 0)).isEqualByComparingTo("100.00");
		assertThat(custom.calculate(2, 2, 0)).isEqualByComparingTo("0.00");
	}

	@Test
	void zeroPenaltyKeepsRatingScore() {
		TrustScorePolicy noPenalty = new TrustScorePolicy(new TrustScoreProperties(new BigDecimal("3.0"), 3,
			BigDecimal.ZERO));

		assertThat(noPenalty.calculate(1, 5, 3)).isEqualByComparingTo("62.50");
	}

	@ParameterizedTest
	@CsvSource({"0, 0, 0", "1, 0, 0", "1, 6, 0", "-1, 3, 0", "0, 1, 1", "1, 5, -1"})
	void rejectsImpossibleTotals(long reviewCount, long ratingSum, long noShowCount) {
		assertThatThrownBy(() -> policy.calculate(reviewCount, ratingSum, noShowCount))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsOutOfRangePolicySettings() {
		assertThatThrownBy(() -> new TrustScoreProperties(new BigDecimal("0.9"), 3, BigDecimal.TEN))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TrustScoreProperties(new BigDecimal("5.1"), 3, BigDecimal.TEN))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TrustScoreProperties(new BigDecimal("3.0"), -1, BigDecimal.TEN))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TrustScoreProperties(new BigDecimal("3.0"), 3, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TrustScoreProperties(new BigDecimal("3.0"), 3, new BigDecimal("-0.01")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TrustScoreProperties(new BigDecimal("3.0"), 3, new BigDecimal("100.01")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TrustScoreProperties(new BigDecimal("3.0"), 3, new BigDecimal("1.005")))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
