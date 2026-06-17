package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.UrgencyLevel;
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
        JobPost jobPost = JobPost.builder()
                .businessId(request.businessId())
                .ownerId(ownerId)
                .categoryId(request.categoryId())
                .storeName(request.storeName())
                .address(request.address())
                .title(request.title())
                .description(request.description())
                .workDate(request.workDate())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .baseHourlyWage(request.baseHourlyWage())
                .extraWage(request.extraWage())
                .recruitCount(request.recruitCount())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .urgencyLevel(UrgencyLevel.valueOf(request.urgencyLevel()))
                .applicationDeadline(request.applicationDeadline())
                .build();
        return jobPostRepository.save(jobPost).getId();
    }
}
