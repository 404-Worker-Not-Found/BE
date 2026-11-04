package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingStatusHistory;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingActorType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.model.MatchingLockTarget;
import com.workernotfound.matching.domain.matching.repository.MatchingConfirmationSagaRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응답 제한시간이 지난 자동·폴백 제안을 만료한다. 잠금 순서는 거절·수락 준비와 같은 지원 → 매칭 → 확정 Saga다.
 * 이미 시작한 수락(확정 진행·보상 필요)은 만료하지 않고 그 결과를 따른다.
 */
@Service
@RequiredArgsConstructor
public class MatchingExpiryService {

	static final String EXPIRED_REASON = "MATCHING_RESPONSE_EXPIRED";

	private final ApplicationRepository applicationRepository;
	private final MatchingRepository matchingRepository;
	private final MatchingStatusHistoryRepository historyRepository;
	private final MatchingConfirmationSagaRepository sagaRepository;
	private final Clock clock;

	@Transactional
	public boolean expire(Long matchingId) {
		LocalDateTime now = LocalDateTime.now(clock);
		MatchingLockTarget target = matchingRepository.findLockTargetById(matchingId).orElse(null);
		if (target == null || applicationRepository.findByIdForUpdate(target.applicationId()).isEmpty()) {
			return false;
		}
		Matching matching = matchingRepository.findByIdForUpdate(matchingId).orElseThrow();
		if (!matching.isResponseExpired(now) || isAcceptanceInProgress(matchingId)) {
			return false;
		}
		matching.expire(now);
		matchingRepository.flush();
		historyRepository.saveAndFlush(MatchingStatusHistory.builder()
			.matching(matching)
			.fromStatus(MatchingStatus.PENDING)
			.toStatus(MatchingStatus.EXPIRED)
			.actorType(MatchingActorType.SYSTEM)
			.reasonCode(EXPIRED_REASON)
			.revision(matching.getRevision())
			.changedAt(now)
			.build());
		return true;
	}

	private boolean isAcceptanceInProgress(Long matchingId) {
		return sagaRepository.findByMatchingIdForUpdate(matchingId)
			.map(saga -> saga.blocksProposalResponse())
			.orElse(false);
	}
}
