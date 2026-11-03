package com.workernotfound.member.domain.review.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 회원 신뢰 점수가 바뀐 사실. aggregate는 회원(MEMBER)이고 revision은 회원별 신뢰 점수 변경 순번이다.
 * 발생 시각은 Stream envelope의 occurredAt으로 전달하고 payload에는 시각을 넣지 않는다.
 */
public record TrustScoreUpdatedEvent(
	String eventId,
	String eventType,
	long memberId,
	long revision,
	int version,
	BigDecimal trustScore,
	long reviewCount,
	String policyVersion
) {
	public static final String TYPE = "TrustScoreUpdated";
	public static final String AGGREGATE_TYPE = "MEMBER";
	public static final int SCHEMA_VERSION = 1;

	public static TrustScoreUpdatedEvent of(long memberId, long revision, BigDecimal trustScore, long reviewCount,
		String policyVersion) {
		return new TrustScoreUpdatedEvent(UUID.randomUUID().toString(), TYPE, memberId, revision, SCHEMA_VERSION,
			trustScore, reviewCount, policyVersion);
	}
}
