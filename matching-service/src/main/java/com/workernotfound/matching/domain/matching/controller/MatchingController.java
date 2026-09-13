package com.workernotfound.matching.domain.matching.controller;

import com.workernotfound.matching.domain.matching.controller.docs.MatchingControllerDocs;
import com.workernotfound.matching.domain.matching.dto.request.CreateManualMatchingRequest;
import com.workernotfound.matching.domain.matching.dto.response.MatchingResponse;
import com.workernotfound.matching.domain.matching.service.MatchingApplicationService;
import com.workernotfound.matching.global.response.ApiResponse;
import com.workernotfound.matching.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/jobs/{jobPostId}/applications/{applicationId}/matchings")
public class MatchingController implements MatchingControllerDocs {

	private final MatchingApplicationService matchingApplicationService;

	@Override
	@PostMapping
	public ResponseEntity<ApiResponse<MatchingResponse>> createManual(
		@AuthenticationPrincipal AuthenticatedMember member,
		@PathVariable Long jobPostId,
		@PathVariable Long applicationId,
		@RequestBody CreateManualMatchingRequest request
	) {
		return ResponseEntity.ok(ApiResponse.success(matchingApplicationService.createManual(
			jobPostId,
			applicationId,
			member.memberId(),
			request
		)));
	}
}
