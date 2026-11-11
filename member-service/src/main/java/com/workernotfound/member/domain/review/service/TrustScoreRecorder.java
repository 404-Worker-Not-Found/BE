package com.workernotfound.member.domain.review.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.member.domain.outbox.MemberOutboxRepository;
import com.workernotfound.member.domain.review.event.TrustScoreUpdatedEvent;
import com.workernotfound.member.domain.review.repository.MemberReviewStatsRepository;
import com.workernotfound.member.domain.review.repository.MemberReviewStatsRepository.Totals;
import com.workernotfound.member.domain.review.repository.MemberTrustScoreRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리뷰 저장·노쇼 기록 트랜잭션 안에서 해당 회원의 신뢰 점수를 다시 계산해 저장하고 TrustScoreUpdated를 Outbox에 남긴다.
 * 호출자는 먼저 같은 트랜잭션에서 신뢰 점수 입력 집계 행(리뷰 수·평점 합·노쇼 횟수)을 갱신해야 한다. 이 행 잠금으로 같은
 * 회원의 갱신이 직렬화되므로 revision과 이벤트 순서가 집계 순서와 같다.
 */
@Service
@RequiredArgsConstructor
public class TrustScoreRecorder {
	private final MemberReviewStatsRepository stats;
	private final MemberTrustScoreRepository trustScores;
	private final MemberOutboxRepository outbox;
	private final TrustScorePolicy policy;
	private final ObjectMapper mapper;

	@Transactional(propagation = Propagation.MANDATORY)
	public void record(Long memberId, LocalDateTime now) {
		Totals totals = stats.findTotalsForUpdate(memberId);
		BigDecimal trustScore = policy.calculate(totals.reviewCount(), totals.ratingSum(), totals.noShowCount());
		long revision = trustScores.save(memberId, trustScore, totals, TrustScorePolicy.VERSION, now);
		TrustScoreUpdatedEvent event = TrustScoreUpdatedEvent.of(memberId, revision, trustScore, totals.reviewCount(),
			totals.noShowCount(), TrustScorePolicy.VERSION);
		outbox.append(event.eventId(), TrustScoreUpdatedEvent.AGGREGATE_TYPE, memberId, event.eventType(), revision,
			event.version(), now, toJson(event));
	}

	private String toJson(TrustScoreUpdatedEvent event) {
		try {
			return mapper.writeValueAsString(event);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("신뢰 점수 이벤트 직렬화에 실패했습니다.", exception);
		}
	}
}
