package com.workernotfound.matching.domain.score.service;

import com.workernotfound.matching.domain.score.event.TrustScoreChangedEvent;
import com.workernotfound.matching.domain.score.model.TrustScoreUpdate;
import com.workernotfound.matching.domain.score.repository.MemberTrustScoreRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * member-service TrustScoreUpdated를 회원별 신뢰 점수 사본에 반영한다. 더 큰 revision만 반영하고, 반영했으면 커밋 뒤
 * 그 회원이 지원 중인 공고의 점수 대기열을 다시 맞춘다. 대기열 갱신이 실패해도 주기적인 대기열 복구가 다시 계산한다.
 */
@Service
@RequiredArgsConstructor
public class TrustScoreProjectionService {

	private final MemberTrustScoreRepository trustScoreRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;

	@Transactional
	public boolean receive(TrustScoreUpdate update) {
		boolean applied = trustScoreRepository.apply(update, LocalDateTime.now(clock));
		if (applied) {
			eventPublisher.publishEvent(new TrustScoreChangedEvent(update.memberId()));
		}
		return applied;
	}
}
