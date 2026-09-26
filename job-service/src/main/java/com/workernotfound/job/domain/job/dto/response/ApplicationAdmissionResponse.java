package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.JobPost;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record ApplicationAdmissionResponse(
        Long admissionId,
        Long jobPostId,
        Long jobVersion,
        Long ownerMemberId,
        Long categoryId,
        LocalDate workDate,
        LocalTime startTime,
        LocalTime endTime,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalDateTime admittedAt,
        LocalDateTime expiresAt
) {
    public static ApplicationAdmissionResponse of(JobApplicationAdmission admission, JobPost jobPost) {
        return new ApplicationAdmissionResponse(
                admission.getId(),
                admission.getJobPostId(),
                admission.getJobVersion(),
                jobPost.getOwnerId(),
                jobPost.getCategoryId(),
                jobPost.getWorkDate(),
                jobPost.getStartTime(),
                jobPost.getEndTime(),
                jobPost.getLatitude(),
                jobPost.getLongitude(),
                admission.getAdmittedAt(),
                admission.getExpiresAt()
        );
    }
}
