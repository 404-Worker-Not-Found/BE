package com.workernotfound.matching.domain.matching.controller;

import com.workernotfound.matching.domain.matching.controller.docs.WorkerMatchingControllerDocs;
import com.workernotfound.matching.domain.matching.dto.request.MatchingListRequest;
import com.workernotfound.matching.domain.matching.dto.response.MatchingListResponse;
import com.workernotfound.matching.domain.matching.dto.response.MatchingResponse;
import com.workernotfound.matching.domain.matching.service.MatchingApplicationService;
import com.workernotfound.matching.global.response.ApiResponse;
import com.workernotfound.matching.global.security.AuthenticatedMember;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/matchings")
public class WorkerMatchingController implements WorkerMatchingControllerDocs {

	private final MatchingApplicationService matchingApplicationService;

	@Override
	@GetMapping
	public ResponseEntity<ApiResponse<MatchingListResponse>> getMatchings(
		@AuthenticationPrincipal AuthenticatedMember member,
		@ModelAttribute MatchingListRequest request
	) {
		int page = request.page() == null ? 0 : request.page();
		int size = request.size() == null ? 20 : request.size();
		return ResponseEntity.ok(ApiResponse.success(
			matchingApplicationService.getMatchings(member.memberId(), page, size)
		));
	}

	@Override
	@GetMapping("/{matchingId}")
	public ResponseEntity<ApiResponse<MatchingResponse>> getMatching(
		@AuthenticationPrincipal AuthenticatedMember member,
		@PathVariable Long matchingId
	) {
		return ResponseEntity.ok(ApiResponse.success(
			matchingApplicationService.getMatching(matchingId, member.memberId())
		));
	}

	@Override
	@PatchMapping("/{matchingId}/decline")
	public ResponseEntity<ApiResponse<MatchingResponse>> decline(
		@AuthenticationPrincipal AuthenticatedMember member,
		@PathVariable Long matchingId
	) {
		return ResponseEntity.ok(ApiResponse.success(
			matchingApplicationService.decline(matchingId, member.memberId())
		));
	}

	@Override
	@PatchMapping("/{matchingId}/accept")
	public ResponseEntity<ApiResponse<MatchingResponse>> accept(
		@AuthenticationPrincipal AuthenticatedMember member,
		@PathVariable Long matchingId
	) {
		return ResponseEntity.ok(ApiResponse.success(
			matchingApplicationService.accept(matchingId, member.memberId())
		));
	}
}
