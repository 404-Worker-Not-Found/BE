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
		matching.cancel();
		matchingRepository.flush();
		historyRepository.saveAndFlush(MatchingStatusHistory.builder()
			.matching(matching)
			.fromStatus(MatchingStatus.PENDING)
			.toStatus(MatchingStatus.CANCELED)
			.actorType(MatchingActorType.WORKER)
			.actorMemberId(workerMemberId)
			.reasonCode(APPLICATION_CANCELED_REASON)
			.revision(matching.getRevision())
			.changedAt(changedAt)
			.build());
	}
}
