package com.workernotfound.matching.domain.score.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MatchingScoreInput(
	Long applicationId,
	LocalDateTime appliedAt,
	BigDecimal rating,
	Integer industryExperienceMonths,
	Boolean online,
	Integer expectedArrivalMinutes,
	BigDecimal noShowProbability
) {
}
