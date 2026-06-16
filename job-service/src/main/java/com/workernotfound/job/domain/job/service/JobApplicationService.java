package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JobApplicationService {

    private final JobPostRepository jobPostRepository;

    @Transactional
    public Long create(Long ownerId, CreateJobRequest request) {
        JobPost jobPost = JobPost.create(
                request.businessId(),
                ownerId,
                request.categoryId(),
                request.title(),
                request.description(),
                request.workDate(),
                request.startTime(),
                request.endTime(),
                request.baseHourlyWage(),
                request.recruitCount()
        );
        return jobPostRepository.save(jobPost).getId();
    }
}
