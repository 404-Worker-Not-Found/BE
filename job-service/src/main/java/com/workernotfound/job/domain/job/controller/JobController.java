package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.controller.docs.JobControllerDocs;
import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import com.workernotfound.job.domain.job.service.JobApplicationService;
import com.workernotfound.job.domain.job.service.JobFindService;
import com.workernotfound.job.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController implements JobControllerDocs {

    private final JobApplicationService jobApplicationService;
    private final JobFindService jobFindService;

    @Override
    @PostMapping
    public ResponseEntity<ApiResponse<Long>> create(
            @RequestHeader("X-Owner-Id") Long ownerId,
            @RequestBody CreateJobRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.create(ownerId, request)
        ));
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<JobDetailResponse>> getDetail(
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobFindService.getJobDetail(id)
        ));
    }

    @Override
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<JobSearchResponse>> search(
            @ModelAttribute JobSearchRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobFindService.searchJobs(request)
        ));
    }
}
