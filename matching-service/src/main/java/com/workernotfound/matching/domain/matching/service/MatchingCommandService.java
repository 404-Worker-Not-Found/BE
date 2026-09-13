package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingStatusHistory;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingActorType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingSelectionType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.matching.exception.MatchingErrorCode;
import com.workernotfound.matching.domain.matching.repository.MatchingRepository;
import com.workernotfound.matching.domain.matching.repository.MatchingStatusHistoryRepository;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.entity.enums.ScoreCalculationStatus;
import com.workernotfound.matching.domain.score.service.MatchingScoreFindService;
import com.workernotfound.matching.global.exception.BusinessException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingCommandService {

	private static final String MANUAL_SELECTION_REASON = "MANUAL_CANDIDATE_SELECTED";

	private final ApplicationRepository applicationRepository;
	private final MatchingRepository matchingRepository;
	private final MatchingStatusHistoryRepository historyRepository;
	private final MatchingScoreFindService scoreFindService;

	@Transactional
	public Matching createManual(
		Long jobPostId,
		Long applicationId,
		Long ownerMemberId,
		Long requestedScoreBatchId
	) {
		Application application = applicationRepository.findByIdForUpdate(applicationId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.MATCHING_FORBIDDEN));
		validateOwner(application, jobPostId, ownerMemberId);

		Matching existing = matchingRepository.findByApplicationId(applicationId).orElse(null);
		if (existing != null) {
			if (existing.getStatus() == MatchingStatus.PENDING
				&& existing.getSelectionType() == MatchingSelectionType.MANUAL) {
				return existing;
			}
			throw new BusinessException(MatchingErrorCode.MATCHING_ALREADY_EXISTS);
		}
		if (application.getStatus() != ApplicationStatus.APPLIED) {
			throw new BusinessException(MatchingErrorCode.APPLICATION_NOT_SELECTABLE);
		}

		MatchingScoreBatch scoreBatch = resolveScoreBatch(jobPostId, requestedScoreBatchId);
		MatchingScoreSnapshot scoreSnapshot = resolveReadySnapshot(scoreBatch, applicationId);
		LocalDateTime selectedAt = LocalDateTime.now();
		Matching matching = matchingRepository.saveAndFlush(Matching.builder()
			.application(application)
			.scoreBatch(scoreBatch)
			.scoreSnapshot(scoreSnapshot)
			.selectionType(MatchingSelectionType.MANUAL)
			.selectedAt(selectedAt)
			.build());
		historyRepository.saveAndFlush(initialHistory(matching, ownerMemberId, selectedAt));
		return matching;
	}

	private void validateOwner(Application application, Long jobPostId, Long ownerMemberId) {
		if (!application.getJobPostId().equals(jobPostId)
			|| application.getOwnerMemberId() == null
			|| !application.getOwnerMemberId().equals(ownerMemberId)) {
			throw new BusinessException(MatchingErrorCode.MATCHING_FORBIDDEN);
		}
	}

	private MatchingScoreBatch resolveScoreBatch(Long jobPostId, Long requestedScoreBatchId) {
		if (requestedScoreBatchId == null) {
			return scoreFindService.findLatestReadyBatch(jobPostId).orElse(null);
		}
		return scoreFindService.findReadyBatch(requestedScoreBatchId, jobPostId)
			.orElseThrow(() -> new BusinessException(MatchingErrorCode.SCORE_BATCH_NOT_FOUND));
	}

	private MatchingScoreSnapshot resolveReadySnapshot(MatchingScoreBatch scoreBatch, Long applicationId) {
		if (scoreBatch == null) {
			return null;
		}
		return scoreFindService.findSnapshot(scoreBatch.getId(), applicationId)
			.filter(snapshot -> snapshot.getCalculationStatus() == ScoreCalculationStatus.READY)
			.orElse(null);
	}

	private MatchingStatusHistory initialHistory(
		Matching matching,
		Long ownerMemberId,
		LocalDateTime selectedAt
	) {
		return MatchingStatusHistory.builder()
			.matching(matching)
			.toStatus(MatchingStatus.PENDING)
			.actorType(MatchingActorType.OWNER)
			.actorMemberId(ownerMemberId)
			.reasonCode(MANUAL_SELECTION_REASON)
			.revision(matching.getRevision())
			.changedAt(selectedAt)
			.build();
	}
}
