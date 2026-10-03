package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.controller.docs.JobInternalControllerDocs;
import com.workernotfound.job.domain.job.dto.request.ApplicationAdmissionRequest;
import com.workernotfound.job.domain.job.dto.request.MatchingSeatReservationRequest;
import com.workernotfound.job.domain.job.dto.response.ApplicationAdmissionResponse;
import com.workernotfound.job.domain.job.dto.response.MatchingSeatReservationCommandResponse;
import com.workernotfound.job.domain.job.dto.response.MatchingSeatReservationResponse;
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

    @Override
    @PostMapping("/{jobPostId}/matching-seat-reservations")
    public ResponseEntity<ApiResponse<MatchingSeatReservationResponse>> reserveMatchingSeat(
            @PathVariable Long jobPostId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MatchingSeatReservationRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.reserveMatchingSeat(jobPostId, request, idempotencyKey)
        ));
    }

    @Override
    @PostMapping("/{jobPostId}/matching-seat-reservations/{reservationId}/confirm")
    public ResponseEntity<ApiResponse<MatchingSeatReservationCommandResponse>> confirmMatchingSeat(
            @PathVariable Long jobPostId,
            @PathVariable Long reservationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.confirmMatchingSeat(jobPostId, reservationId, idempotencyKey)
        ));
    }

    @Override
    @PostMapping("/{jobPostId}/matching-seat-reservations/{reservationId}/release")
    public ResponseEntity<ApiResponse<MatchingSeatReservationCommandResponse>> releaseMatchingSeat(
            @PathVariable Long jobPostId,
            @PathVariable Long reservationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.releaseMatchingSeat(jobPostId, reservationId, idempotencyKey)
        ));
    }
}
