package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingStatusHistory;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingActorType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.exception.MatchingErrorCode;
import com.workernotfound.matching.domain.matching.model.MatchingLockTarget;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import com.workernotfound.matching.global.exception.BusinessException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingDeclineService {

	private static final String DECLINED_REASON = "MATCHING_DECLINED_BY_WORKER";

	private final ApplicationRepository applicationRepository;
	private final MatchingRepository matchingRepository;
	private final MatchingStatusHistoryRepository historyRepository;

	@Transactional
	public Matching decline(Long matchingId, Long workerMemberId) {
		MatchingLockTarget lockTarget = matchingRepository.findLockTargetById(matchingId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.MATCHING_NOT_FOUND));
		validateWorker(lockTarget.workerMemberId(), workerMemberId);
		applicationRepository.findByIdForUpdate(lockTarget.applicationId())
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.MATCHING_NOT_FOUND));
		Matching matching = matchingRepository.findByIdForUpdate(matchingId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.MATCHING_NOT_FOUND));
		validateWorker(matching.getWorkerMemberId(), workerMemberId);
		if (matching.getStatus() == MatchingStatus.DECLINED) {
			return matching;
		}
		if (matching.getStatus() != MatchingStatus.PENDING) {
			throw new BusinessException(MatchingErrorCode.MATCHING_STATE_CONFLICT);
		}

		LocalDateTime changedAt = LocalDateTime.now();
		matching.decline();
		matchingRepository.flush();
		historyRepository.saveAndFlush(MatchingStatusHistory.builder()
			.matching(matching)
			.fromStatus(MatchingStatus.PENDING)
			.toStatus(MatchingStatus.DECLINED)
			.actorType(MatchingActorType.WORKER)
			.actorMemberId(workerMemberId)
			.reasonCode(DECLINED_REASON)
			.revision(matching.getRevision())
			.changedAt(changedAt)
			.build());
		return matching;
	}

	private void validateWorker(Long matchingWorkerMemberId, Long workerMemberId) {
		if (!matchingWorkerMemberId.equals(workerMemberId)) {
			throw new BusinessException(MatchingErrorCode.MATCHING_FORBIDDEN);
		}
	}
}
