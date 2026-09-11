package com.workernotfound.matching.domain.application.controller;

import com.workernotfound.matching.domain.application.controller.docs.ApplicationControllerDocs;
import com.workernotfound.matching.domain.application.dto.request.ApplicationListRequest;
import com.workernotfound.matching.domain.application.dto.request.CreateApplicationRequest;
import com.workernotfound.matching.domain.application.dto.response.ApplicationListResponse;
import com.workernotfound.matching.domain.application.dto.response.ApplicationResponse;
import com.workernotfound.matching.domain.application.service.ApplicationApplicationService;
import com.workernotfound.matching.global.response.ApiResponse;
import com.workernotfound.matching.global.security.AuthenticatedMember;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/applications")
public class ApplicationController implements ApplicationControllerDocs {

	private final ApplicationApplicationService applicationApplicationService;

	@Override
	@PostMapping
	public ResponseEntity<ApiResponse<ApplicationResponse>> create(
		@AuthenticationPrincipal AuthenticatedMember member,
		@Valid @RequestBody CreateApplicationRequest request
	) {
		return ResponseEntity.ok(ApiResponse.success(
			applicationApplicationService.create(member.memberId(), request)
		));
	}

	@Override
	@GetMapping("/{applicationId}")
	public ResponseEntity<ApiResponse<ApplicationResponse>> getApplication(
		@AuthenticationPrincipal AuthenticatedMember member,
		@PathVariable Long applicationId
	) {
		return ResponseEntity.ok(ApiResponse.success(
			applicationApplicationService.getApplication(applicationId, member.memberId())
		));
	}

	@Override
	@GetMapping
	public ResponseEntity<ApiResponse<ApplicationListResponse>> getApplications(
		@AuthenticationPrincipal AuthenticatedMember member,
		@Valid @ModelAttribute ApplicationListRequest request
	) {
		int page = request.page() == null ? 0 : request.page();
		int size = request.size() == null ? 20 : request.size();
		return ResponseEntity.ok(ApiResponse.success(
			applicationApplicationService.getApplications(member.memberId(), page, size)
		));
	}

	@Override
	@PatchMapping("/{applicationId}/cancel")
	public ResponseEntity<ApiResponse<ApplicationResponse>> cancel(
		@AuthenticationPrincipal AuthenticatedMember member,
		@PathVariable Long applicationId
	) {
		return ResponseEntity.ok(ApiResponse.success(
			applicationApplicationService.cancel(applicationId, member.memberId())
		));
	}
}
