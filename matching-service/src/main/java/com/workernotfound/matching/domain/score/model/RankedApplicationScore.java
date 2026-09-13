package com.workernotfound.matching.domain.score.model;

import java.math.BigDecimal;

public record RankedApplicationScore(
	Long applicationId,
	BigDecimal totalScore
) {
}
