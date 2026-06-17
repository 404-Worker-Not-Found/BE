package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JobApplicationService {

    private final JobCommandService jobCommandService;
    private final JobFindService jobFindService;

    public Long create(Long ownerId, CreateJobRequest request) {
        return jobCommandService.create(ownerId, request);
    }

    public JobDetailResponse getJobDetail(Long jobId) {
        return jobFindService.findJobDetail(jobId);
    }

    public JobSearchResponse getJobs(JobSearchRequest request) {
        return jobFindService.findJobs(request);
    }
}
