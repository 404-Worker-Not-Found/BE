package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.dto.request.UpdatePaymentTermsRequest;
import com.workernotfound.job.domain.job.dto.response.JobPaymentChangeResponse;
import com.workernotfound.job.domain.job.dto.request.FundingStatusRequest;
import com.workernotfound.job.domain.job.dto.response.FundingStatusResponse;
import com.workernotfound.job.domain.job.dto.response.ApplicationAdmissionResponse;
import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.request.OwnerJobSearchRequest;
import com.workernotfound.job.domain.job.dto.response.OwnerJobListResponse;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobPaymentOrderResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import com.workernotfound.job.domain.job.dto.request.MatchingSeatReservationRequest;
import com.workernotfound.job.domain.job.dto.response.MatchingSeatReservationCommandResponse;
import com.workernotfound.job.domain.job.dto.response.MatchingSeatReservationResponse;
import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JobApplicationService {

    private final JobCommandService jobCommandService;
    private final JobFindService jobFindService;
    private final JobApplicationAdmissionCommandService jobApplicationAdmissionCommandService;
    private final MatchingSeatReservationCommandService matchingSeatReservationCommandService;
    private final JobFundingStatusCommandService jobFundingStatusCommandService;
    private final JobPaymentChangeCommandService jobPaymentChangeCommandService;

    public Long create(Long ownerId, CreateJobRequest request) {
        return jobCommandService.create(ownerId, request);
    }

    public JobDetailResponse getJobDetail(Long jobId, Long viewerMemberId) {
        return jobFindService.findJobDetail(jobId, viewerMemberId);
    }

    public JobPaymentOrderResponse getJobPaymentOrder(Long jobId, Long ownerMemberId) {
        return jobFindService.findJobPaymentOrder(jobId, ownerMemberId);
    }

    public JobPaymentChangeResponse changePaymentTerms(
            Long jobId,
            Long ownerMemberId,
            UpdatePaymentTermsRequest request,
            String idempotencyKey
    ) {
        return toResponse(jobPaymentChangeCommandService.changeTerms(
                jobId, ownerMemberId, request.toTerms(), idempotencyKey));
    }

    public JobPaymentChangeResponse retryPayment(Long jobId, Long ownerMemberId, String idempotencyKey) {
        return toResponse(jobPaymentChangeCommandService.retryPayment(jobId, ownerMemberId, idempotencyKey));
    }

    private JobPaymentChangeResponse toResponse(JobPaymentChange change) {
        return JobPaymentChangeResponse.of(change.request(), change.command());
    }

    public JobSearchResponse getJobs(JobSearchRequest request) {
        return jobFindService.findJobs(request);
    }

    public OwnerJobListResponse getOwnerJobs(Long ownerMemberId, OwnerJobSearchRequest request) {
        return jobFindService.findOwnerJobs(ownerMemberId, request);
    }

    public ApplicationAdmissionResponse createApplicationAdmission(
            Long jobPostId,
            Long workerMemberId,
            String idempotencyKey
    ) {
        JobApplicationAdmission admission =
                jobApplicationAdmissionCommandService.create(jobPostId, workerMemberId, idempotencyKey);
        return ApplicationAdmissionResponse.of(admission);
    }

    public MatchingSeatReservationResponse reserveMatchingSeat(
            Long jobPostId,
            MatchingSeatReservationRequest request,
            String idempotencyKey
    ) {
        return MatchingSeatReservationResponse.of(matchingSeatReservationCommandService.reserve(
                jobPostId,
                request.matchingId(),
                request.applicationId(),
                request.workerMemberId(),
                idempotencyKey
        ));
    }

    public MatchingSeatReservationCommandResponse confirmMatchingSeat(
            Long jobPostId,
            Long reservationId,
            String idempotencyKey
    ) {
        return MatchingSeatReservationCommandResponse.of(
                matchingSeatReservationCommandService.confirm(jobPostId, reservationId, idempotencyKey));
    }

    public MatchingSeatReservationCommandResponse releaseMatchingSeat(
            Long jobPostId,
            Long reservationId,
            String idempotencyKey
    ) {
        return MatchingSeatReservationCommandResponse.of(
                matchingSeatReservationCommandService.release(jobPostId, reservationId, idempotencyKey));
    }

    public FundingStatusResponse receiveFundingStatus(
            Long jobPostId,
            FundingStatusRequest request,
            String idempotencyKey
    ) {
        return FundingStatusResponse.of(
                jobFundingStatusCommandService.receive(jobPostId, request.toNotification(), idempotencyKey));
    }
}
