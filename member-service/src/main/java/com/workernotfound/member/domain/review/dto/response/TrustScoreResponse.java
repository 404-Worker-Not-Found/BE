package com.workernotfound.member.domain.review.dto.response;

import com.workernotfound.member.domain.review.repository.MemberTrustScoreRepository.TrustScoreSummary;
import java.math.BigDecimal;
import java.time.LocalDateTime;

// 회원의 현재 신뢰 점수. 받은 리뷰와 노쇼가 모두 없으면 점수와 갱신 시각은 null이다.
public record TrustScoreResponse(
	Long memberId,
	BigDecimal trustScore,
	long reviewCount,
	long noShowCount,
	LocalDateTime updatedAt
) {
	public static TrustScoreResponse from(Long memberId, TrustScoreSummary summary) {
		return new TrustScoreResponse(memberId, summary.trustScore(), summary.reviewCount(), summary.noShowCount(),
			summary.updatedAt());
	}

	public static TrustScoreResponse empty(Long memberId) {
		return new TrustScoreResponse(memberId, null, 0, 0, null);
	}
}
