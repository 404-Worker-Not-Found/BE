package com.workernotfound.matching.domain.matching.service;

import com.workernotfound.matching.domain.matching.dto.request.CreateManualMatchingRequest;
import com.workernotfound.matching.domain.matching.dto.response.MatchingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingApplicationService {

	private final MatchingCommandService matchingCommandService;

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
}
