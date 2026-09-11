package com.workernotfound.matching.domain.score.model;

import java.math.BigDecimal;
import java.util.Objects;

public record MatchingScoreResult(
	BigDecimal totalScore,
	BigDecimal appliedTimeScore,
	BigDecimal ratingScore,
	BigDecimal experienceScore,
	BigDecimal activityScore,
	BigDecimal arrivalScore,
	BigDecimal noShowScore,
	Integer expectedArrivalMinutes,
	BigDecimal noShowProbability
) {

	public MatchingScoreResult {
		Objects.requireNonNull(totalScore, "계산 완료 점수에는 총점이 필요합니다.");
	}
}
