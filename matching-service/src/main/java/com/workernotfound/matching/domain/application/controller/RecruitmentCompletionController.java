package com.workernotfound.matching.domain.application.controller;

import com.workernotfound.matching.domain.application.controller.docs.RecruitmentCompletionControllerDocs;
import com.workernotfound.matching.domain.application.service.RecruitmentCompletionService;
import com.workernotfound.matching.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/applications/internal/jobs")
public class RecruitmentCompletionController implements RecruitmentCompletionControllerDocs {

	private final RecruitmentCompletionService recruitmentCompletionService;

	@Override
	@PostMapping("/{jobPostId}/recruitment-completion")
	public ResponseEntity<ApiResponse<Void>> complete(
		@PathVariable Long jobPostId,
		@RequestHeader("Idempotency-Key") String correlationId
	) {
		recruitmentCompletionService.complete(jobPostId, correlationId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}
}
