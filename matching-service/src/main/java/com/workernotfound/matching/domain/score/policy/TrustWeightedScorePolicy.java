package com.workernotfound.matching.domain.score.policy;

import com.workernotfound.matching.domain.score.model.MatchingScoreResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 지원 시각 점수와 member-service 신뢰 점수(0~100)를 같은 비중으로 합친다(임시 정책).
 * 신뢰 점수가 없는 지원자(받은 리뷰 없음)는 평점 입력을 누락으로 기록하고 그 비중을 지원 시각 점수에 재분배한다.
 */
@Component
@RequiredArgsConstructor
public class TrustWeightedScorePolicy {

	public static final String VERSION = "application-time-trust-v1";

	private static final BigDecimal APPLIED_TIME_WEIGHT = new BigDecimal("0.5");
	private static final BigDecimal TRUST_WEIGHT = new BigDecimal("0.5");
	private static final int SCORE_SCALE = 4;

	private final ApplicationTimeScorePolicy applicationTimeScorePolicy;

	public MatchingScoreResult calculate(int position, int candidateCount, BigDecimal trustScore) {
		BigDecimal appliedTimeScore = applicationTimeScorePolicy.calculate(position, candidateCount).appliedTimeScore();
		if (trustScore == null) {
			return result(appliedTimeScore, appliedTimeScore, null);
		}
		BigDecimal ratingScore = trustScore.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
		BigDecimal totalScore = appliedTimeScore.multiply(APPLIED_TIME_WEIGHT)
			.add(ratingScore.multiply(TRUST_WEIGHT))
			.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
		return result(totalScore, appliedTimeScore, ratingScore);
	}

	private MatchingScoreResult result(BigDecimal totalScore, BigDecimal appliedTimeScore, BigDecimal ratingScore) {
		return new MatchingScoreResult(
			totalScore,
			appliedTimeScore,
			ratingScore,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}
}
