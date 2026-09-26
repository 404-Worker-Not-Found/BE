package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.dto.response.ApplicationAdmissionResponse;
import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JobApplicationService {

    private final JobCommandService jobCommandService;
    private final JobFindService jobFindService;
    private final JobApplicationAdmissionCommandService jobApplicationAdmissionCommandService;

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
        return ApplicationAdmissionResponse.of(admission, jobFindService.findJobPost(jobPostId));
    }
}
