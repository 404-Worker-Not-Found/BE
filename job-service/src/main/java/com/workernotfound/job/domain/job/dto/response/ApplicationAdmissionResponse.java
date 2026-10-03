package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;

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
    // 공고를 다시 읽지 않는다. 승인에 저장된 발급 당시 스냅샷만 사용해야 재요청 응답이 같다.
    public static ApplicationAdmissionResponse of(JobApplicationAdmission admission) {
        return new ApplicationAdmissionResponse(
                admission.getId(),
                admission.getJobPostId(),
                admission.getJobVersion(),
                admission.getOwnerMemberId(),
                admission.getCategoryId(),
                admission.getWorkDate(),
                admission.getStartTime(),
                admission.getEndTime(),
                admission.getLatitude(),
                admission.getLongitude(),
                admission.getAdmittedAt(),
                admission.getExpiresAt()
        );
    }
}
