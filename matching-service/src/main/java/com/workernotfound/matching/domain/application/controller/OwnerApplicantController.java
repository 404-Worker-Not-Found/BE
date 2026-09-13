package com.workernotfound.matching.domain.application.controller;

import com.workernotfound.matching.domain.application.controller.docs.OwnerApplicantControllerDocs;
import com.workernotfound.matching.domain.application.dto.request.OwnerApplicantDetailRequest;
import com.workernotfound.matching.domain.application.dto.request.OwnerApplicantListRequest;
import com.workernotfound.matching.domain.application.dto.response.OwnerApplicantListResponse;
import com.workernotfound.matching.domain.application.dto.response.OwnerApplicantResponse;
import com.workernotfound.matching.domain.application.service.OwnerApplicantApplicationService;
import com.workernotfound.matching.global.response.ApiResponse;
import com.workernotfound.matching.global.security.AuthenticatedMember;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/jobs/{jobPostId}/applications")
public class OwnerApplicantController implements OwnerApplicantControllerDocs {

	private final OwnerApplicantApplicationService ownerApplicantApplicationService;

	@Override
	@GetMapping
	public ResponseEntity<ApiResponse<OwnerApplicantListResponse>> getApplicants(
		@AuthenticationPrincipal AuthenticatedMember member,
		@PathVariable Long jobPostId,
		@Valid @ModelAttribute OwnerApplicantListRequest request
	) {
		int page = request.page() == null ? 0 : request.page();
		int size = request.size() == null ? 20 : request.size();
		return ResponseEntity.ok(ApiResponse.success(ownerApplicantApplicationService.getApplicants(
			jobPostId,
			member.memberId(),
			request.scoreBatchId(),
			page,
			size
		)));
	}

	@Override
	@GetMapping("/{applicationId}")
	public ResponseEntity<ApiResponse<OwnerApplicantResponse>> getApplicant(
		@AuthenticationPrincipal AuthenticatedMember member,
		@PathVariable Long jobPostId,
		@PathVariable Long applicationId,
		@Valid @ModelAttribute OwnerApplicantDetailRequest request
	) {
		return ResponseEntity.ok(ApiResponse.success(ownerApplicantApplicationService.getApplicant(
			jobPostId,
			applicationId,
			member.memberId(),
			request.scoreBatchId()
		)));
	}
}
