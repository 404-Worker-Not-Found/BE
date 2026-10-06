package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.controller.docs.JobControllerDocs;
import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.request.OwnerJobSearchRequest;
import com.workernotfound.job.domain.job.dto.request.UpdatePaymentTermsRequest;
import com.workernotfound.job.domain.job.dto.response.JobCloseResponse;
import com.workernotfound.job.domain.job.dto.response.JobPaymentChangeResponse;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobPaymentOrderResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import com.workernotfound.job.domain.job.dto.response.OwnerJobListResponse;
import com.workernotfound.job.domain.job.service.JobApplicationService;
import com.workernotfound.job.global.response.ApiResponse;
import com.workernotfound.job.global.security.MemberClaims;
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
            @RequestBody CreateJobRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.create(claims.memberId(), request)
        ));
    }

    @Override
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<OwnerJobListResponse>> getMyJobs(
            @AuthenticationPrincipal MemberClaims claims,
            @ModelAttribute OwnerJobSearchRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.getOwnerJobs(claims.memberId(), request)
        ));
    }

    @Override
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<JobDetailResponse>> getDetail(
            @AuthenticationPrincipal MemberClaims claims,
            @PathVariable Long id
    ) {
        // 토큰 없는 조회도 허용한다. 비공개 공고의 점주 확인에만 인증된 회원 ID를 쓴다.
        Long viewerMemberId = claims == null ? null : claims.memberId();
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.getJobDetail(id, viewerMemberId)
        ));
    }

    @Override
    @GetMapping("/{id}/payment-order")
    public ResponseEntity<ApiResponse<JobPaymentOrderResponse>> getPaymentOrder(
            @AuthenticationPrincipal MemberClaims claims,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.getJobPaymentOrder(id, claims.memberId())
        ));
    }

    @Override
    @PutMapping("/{id}/payment-terms")
    public ResponseEntity<ApiResponse<JobPaymentChangeResponse>> changePaymentTerms(
            @AuthenticationPrincipal MemberClaims claims,
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody UpdatePaymentTermsRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.changePaymentTerms(id, claims.memberId(), request, idempotencyKey)
        ));
    }

    @Override
    @PostMapping("/{id}/payment-order/retries")
    public ResponseEntity<ApiResponse<JobPaymentChangeResponse>> retryPayment(
            @AuthenticationPrincipal MemberClaims claims,
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.retryPayment(id, claims.memberId(), idempotencyKey)
        ));
    }

    @Override
    @PostMapping("/{id}/close")
    public ResponseEntity<ApiResponse<JobCloseResponse>> close(
            @AuthenticationPrincipal MemberClaims claims,
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.closeJob(id, claims.memberId(), idempotencyKey)
        ));
    }

    @Override
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<JobSearchResponse>> search(
            @ModelAttribute JobSearchRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                jobApplicationService.getJobs(request)
        ));
    }
}
