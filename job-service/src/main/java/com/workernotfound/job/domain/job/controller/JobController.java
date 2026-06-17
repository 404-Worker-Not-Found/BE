package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.controller.docs.JobControllerDocs;
import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import com.workernotfound.job.domain.job.service.JobApplicationService;
import com.workernotfound.job.global.response.ApiResponse;
import com.workernotfound.job.global.security.MemberClaims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController implements JobControllerDocs {

    private final JobApplicationService jobApplicationService;

    @Override
    @PostMapping
    public ResponseEntity<ApiResponse<Long>> create(
            @AuthenticationPrincipal MemberClaims claims,
            @Valid @RequestBody CreateJobRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.create(claims.memberId(), request)
        ));
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<JobDetailResponse>> getDetail(
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.getJobDetail(id)
        ));
    }

    @Override
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<JobSearchResponse>> search(
            @Valid @ModelAttribute JobSearchRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.getJobs(request)
        ));
    }
}
