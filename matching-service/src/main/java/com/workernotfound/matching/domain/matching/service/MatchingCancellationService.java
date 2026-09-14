package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingStatusHistory;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingActorType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingConfirmationSagaRepository;
import com.workernotfound.matching.domain.application.exception.ApplicationErrorCode;
import com.workernotfound.matching.global.exception.BusinessException;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingCancellationService {

	private static final String APPLICATION_CANCELED_REASON = "APPLICATION_CANCELED_BY_WORKER";
	private static final String RECRUITMENT_COMPLETED_REASON = "RECRUITMENT_COMPLETED";

	private final MatchingRepository matchingRepository;
	private final MatchingStatusHistoryRepository historyRepository;
	private final MatchingConfirmationSagaRepository sagaRepository;

	@Transactional
	public void cancelPendingByApplication(Long applicationId, Long workerMemberId, LocalDateTime changedAt) {
		Matching matching = matchingRepository.findByApplicationId(applicationId).orElse(null);
		if (matching == null || matching.getStatus() != MatchingStatus.PENDING) {
			return;
		}
		sagaRepository.findByMatchingIdForUpdate(matching.getId()).ifPresent(saga -> {
			if (saga.blocksProposalResponse()) {
				throw new BusinessException(ApplicationErrorCode.APPLICATION_STATE_CONFLICT);
			}
		});
		cancel(matching, MatchingActorType.WORKER, workerMemberId, APPLICATION_CANCELED_REASON, changedAt);
	}

	@Transactional
	public void cancelPendingForRecruitmentCompletion(Long applicationId, LocalDateTime changedAt) {
		Matching matching = matchingRepository.findByApplicationId(applicationId).orElse(null);
		if (matching == null || matching.getStatus() != MatchingStatus.PENDING) {
			return;
		}
		sagaRepository.findByMatchingIdForUpdate(matching.getId()).ifPresent(saga -> {
			if (saga.blocksProposalResponse()) {
				throw new BusinessException(ApplicationErrorCode.RECRUITMENT_COMPLETION_IN_PROGRESS);
			}
		});
		cancel(matching, MatchingActorType.SYSTEM, null, RECRUITMENT_COMPLETED_REASON, changedAt);
	}

	private void cancel(
		Matching matching,
		MatchingActorType actorType,
		Long actorMemberId,
		String reasonCode,
		LocalDateTime changedAt
	) {
		matching.cancel();
		matchingRepository.flush();
		historyRepository.saveAndFlush(MatchingStatusHistory.builder()
			.matching(matching)
			.fromStatus(MatchingStatus.PENDING)
			.toStatus(MatchingStatus.CANCELED)
			.actorType(actorType)
			.actorMemberId(actorMemberId)
			.reasonCode(reasonCode)
			.revision(matching.getRevision())
			.changedAt(changedAt)
			.build());
	}
}
