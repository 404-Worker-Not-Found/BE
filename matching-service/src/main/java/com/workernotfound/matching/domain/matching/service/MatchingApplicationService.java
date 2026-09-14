package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.matching.dto.request.CreateManualMatchingRequest;
import com.workernotfound.matching.domain.matching.dto.response.MatchingListResponse;
import com.workernotfound.matching.domain.matching.dto.response.MatchingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingApplicationService {

	private final MatchingCommandService matchingCommandService;
	private final MatchingFindService matchingFindService;
	private final MatchingDeclineService matchingDeclineService;
	private final MatchingConfirmationService matchingConfirmationService;

	@Transactional
	public MatchingResponse createManual(
		Long jobPostId,
		Long applicationId,
		Long ownerMemberId,
		CreateManualMatchingRequest request
	) {
		return MatchingResponse.from(matchingCommandService.createManual(
			jobPostId,
			applicationId,
			ownerMemberId,
			request.scoreBatchId()
		));
	}

	@Transactional(readOnly = true)
	public MatchingResponse getMatching(Long matchingId, Long workerMemberId) {
		return MatchingResponse.from(matchingFindService.findOwnedMatching(matchingId, workerMemberId));
	}

	@Transactional(readOnly = true)
	public MatchingListResponse getMatchings(Long workerMemberId, int page, int size) {
		return MatchingListResponse.from(matchingFindService.findWorkerMatchings(workerMemberId, page, size));
	}

	@Transactional
	public MatchingResponse decline(Long matchingId, Long workerMemberId) {
		return MatchingResponse.from(matchingDeclineService.decline(matchingId, workerMemberId));
	}

	public MatchingResponse accept(Long matchingId, Long workerMemberId) {
		return MatchingResponse.from(matchingConfirmationService.accept(matchingId, workerMemberId));
	}
}
