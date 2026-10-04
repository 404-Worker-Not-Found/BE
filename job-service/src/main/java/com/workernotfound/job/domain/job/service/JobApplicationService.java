package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.dto.response.ApplicationAdmissionResponse;
import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
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

    public Long create(Long ownerId, CreateJobRequest request) {
        return jobCommandService.create(ownerId, request);
    }

    public JobDetailResponse getJobDetail(Long jobId) {
        return jobFindService.findJobDetail(jobId);
    }

    public JobSearchResponse getJobs(JobSearchRequest request) {
        return jobFindService.findJobs(request);
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
}
