package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.service.JobApplicationService;
import com.workernotfound.job.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobApplicationService jobApplicationService;

    @PostMapping
    public ResponseEntity<ApiResponse<Long>> create(
            @RequestHeader("X-Owner-Id") Long ownerId,
            @RequestBody CreateJobRequest request
    ) {
        Long jobId = jobApplicationService.create(ownerId, request);
        return ResponseEntity.ok(ApiResponse.ok(jobId));
    }
}
