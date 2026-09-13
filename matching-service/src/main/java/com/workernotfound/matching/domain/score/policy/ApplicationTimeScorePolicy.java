package com.workernotfound.matching.domain.score.policy;

import com.workernotfound.matching.domain.score.model.MatchingScoreResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class ApplicationTimeScorePolicy {

	public static final String VERSION = "application-time-v1";

	private static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);
	private static final int SCORE_SCALE = 4;

	public MatchingScoreResult calculate(int position, int candidateCount) {
		validatePosition(position, candidateCount);
		BigDecimal appliedTimeScore = BigDecimal.valueOf(candidateCount - position)
			.multiply(MAX_SCORE)
			.divide(BigDecimal.valueOf(candidateCount), SCORE_SCALE, RoundingMode.HALF_UP);
		return new MatchingScoreResult(
			appliedTimeScore,
			appliedTimeScore,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	private void validatePosition(int position, int candidateCount) {
		if (candidateCount <= 0 || position < 0 || position >= candidateCount) {
			throw new IllegalArgumentException("지원 순서와 전체 지원자 수가 올바르지 않습니다.");
		}
	}
}
