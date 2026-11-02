package com.workernotfound.matching.domain.score.model;

import java.math.BigDecimal;

// member-service TrustScoreUpdated v1에서 점수 입력으로 쓰는 값.
public record TrustScoreUpdate(
	String eventId,
	long memberId,
	long revision,
	BigDecimal trustScore,
	long reviewCount,
	String policyVersion
) {
}
