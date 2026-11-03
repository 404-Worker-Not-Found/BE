package com.workernotfound.matching.domain.score;

import com.workernotfound.matching.domain.score.model.MatchingScoreResult;
import com.workernotfound.matching.domain.score.policy.ApplicationTimeScorePolicy;
import com.workernotfound.matching.domain.score.policy.TrustWeightedScorePolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 지원 시각 점수와 신뢰 점수를 50:50으로 합치고, 신뢰 점수가 없으면 지원 시각 점수만 쓴다.
class TrustWeightedScorePolicyTests {
	private final TrustWeightedScorePolicy policy = new TrustWeightedScorePolicy(new ApplicationTimeScorePolicy());

	@Test
	void averagesAppliedTimeAndTrustScores() {
		MatchingScoreResult result = policy.calculate(1, 3, new BigDecimal("83.33"));

		assertThat(result.appliedTimeScore()).isEqualByComparingTo("66.6667");
		assertThat(result.ratingScore()).isEqualByComparingTo("83.3300");
		assertThat(result.totalScore()).isEqualByComparingTo("74.9984"); // 33.33335 + 41.665 = 74.99835
	}

	@Test
	void redistributesTrustWeightToAppliedTimeWhenTrustScoreIsMissing() {
		MatchingScoreResult result = policy.calculate(0, 2, null);

		assertThat(result.totalScore()).isEqualByComparingTo("100.0000");
		assertThat(result.ratingScore()).isNull();
	}

	@Test
	void keepsApplicationPositionValidation() {
		assertThatThrownBy(() -> policy.calculate(2, 2, BigDecimal.TEN)).isInstanceOf(IllegalArgumentException.class);
	}
}
