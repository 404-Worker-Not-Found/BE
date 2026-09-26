package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.controller.docs.JobInternalControllerDocs;
import com.workernotfound.job.domain.job.dto.request.ApplicationAdmissionRequest;
import com.workernotfound.job.domain.job.dto.response.ApplicationAdmissionResponse;
import com.workernotfound.job.domain.job.service.JobApplicationService;
import com.workernotfound.job.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs/internal")
@RequiredArgsConstructor
public class JobInternalController implements JobInternalControllerDocs {

    private final JobApplicationService jobApplicationService;

    @Override
    @PostMapping("/{jobPostId}/application-admissions")
    public ResponseEntity<ApiResponse<ApplicationAdmissionResponse>> createApplicationAdmission(
            @PathVariable Long jobPostId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ApplicationAdmissionRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.createApplicationAdmission(
                        jobPostId,
                        request.workerMemberId(),
                        idempotencyKey
                )
        ));
    }
}
