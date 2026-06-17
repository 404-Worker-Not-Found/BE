package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.UrgencyLevel;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.port.BusinessValidator;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class JobCommandService {

    private final JobPostRepository jobPostRepository;
    private final BusinessValidator businessValidator;
    private final CategoryFindService categoryFindService;

    @Transactional
    public Long create(Long ownerId, CreateJobRequest request) {
        businessValidator.validateOwnership(request.businessId(), ownerId);
        categoryFindService.findCategory(request.categoryId());
        validateWorkTime(request);
        validateApplicationDeadline(request);

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
                .endTimeNextDay(request.isEndTimeNextDay())
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

    private void validateWorkTime(CreateJobRequest request) {
        if (!request.isEndTimeNextDay() && !request.endTime().isAfter(request.startTime())) {
            throw new BusinessException(JobErrorCode.INVALID_WORK_TIME);
        }
    }

    private void validateApplicationDeadline(CreateJobRequest request) {
        LocalDateTime now = LocalDateTime.now();
        if (!request.applicationDeadline().isAfter(now)) {
            throw new BusinessException(JobErrorCode.INVALID_APPLICATION_DEADLINE, "지원 마감 시간은 현재 시간 이후여야 합니다.");
        }
        if (!request.applicationDeadline().isBefore(request.workDate().atTime(request.startTime()))) {
            throw new BusinessException(JobErrorCode.INVALID_APPLICATION_DEADLINE, "지원 마감 시간은 근무 시작 시간 이전이어야 합니다.");
        }
    }
}
