package com.workernotfound.matching.domain.application.controller;

import com.workernotfound.matching.domain.application.controller.docs.ApplicationStateControllerDocs;
import com.workernotfound.matching.domain.application.dto.response.JobApplicationStatesResponse;
import com.workernotfound.matching.domain.application.service.ApplicationFindService;
import com.workernotfound.matching.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/applications/internal/jobs")
public class ApplicationStateController implements ApplicationStateControllerDocs {

	private final ApplicationFindService applicationFindService;

	@Override
	@GetMapping("/{jobPostId}/application-states")
	public ResponseEntity<ApiResponse<JobApplicationStatesResponse>> getApplicationStates(
		@PathVariable Long jobPostId
	) {
		var applications = applicationFindService.findJobApplications(jobPostId);
		return ResponseEntity.ok(ApiResponse.success(JobApplicationStatesResponse.of(jobPostId, applications)));
	}
}
