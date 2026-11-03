package com.workernotfound.matching.external.redis.application;

import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.score.event.TrustScoreChangedEvent;
import com.workernotfound.matching.domain.score.service.MatchingScoreQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 신뢰 점수 사본이 바뀌면 그 회원이 지원 중인 공고마다 공고별 잠금 아래에서 점수 대기열을 다시 맞춘다.
 * 실패한 공고는 로그만 남기고 다음 공고를 계속 처리하며, 주기적인 대기열 복구가 다시 계산한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrustScoreQueueListener {

	private final ApplicationRepository applicationRepository;
	private final ApplicationQueueLockManager lockManager;
	private final MatchingScoreQueueService matchingScoreQueueService;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void refreshQueues(TrustScoreChangedEvent event) {
		applicationRepository.findAppliedJobPostIdsByWorkerMemberId(event.memberId())
			.forEach(jobPostId -> refreshQueue(event.memberId(), jobPostId));
	}

	private void refreshQueue(Long memberId, Long jobPostId) {
		try {
			lockManager.execute(jobPostId, fenceToken -> matchingScoreQueueService.synchronize(jobPostId, fenceToken));
		} catch (RuntimeException exception) {
			log.warn("신뢰 점수 변경 후 점수 대기열 갱신에 실패했습니다. memberId={}, jobPostId={}", memberId, jobPostId,
				exception);
		}
	}
}
