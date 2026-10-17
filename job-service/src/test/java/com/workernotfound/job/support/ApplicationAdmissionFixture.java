package com.workernotfound.job.support;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import java.time.LocalDateTime;
import java.util.UUID;

public final class ApplicationAdmissionFixture {

    private ApplicationAdmissionFixture() {
    }

    // 공개된 공고에 RESERVED 승인을 저장한다. 발급 시각은 실제 TTL 기본값과 같은 5분 전으로 둔다.
    public static JobApplicationAdmission saveReserved(
            JobPostRepository jobPostRepository,
            JobApplicationAdmissionRepository admissionRepository,
            Long workerMemberId,
            LocalDateTime expiresAt
    ) {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().build()));
        return admissionRepository.save(JobApplicationAdmission.builder()
                .jobPostId(jobPost.getId())
                .workerMemberId(workerMemberId)
                .idempotencyKey("admission-" + UUID.randomUUID())
                .jobVersion(jobPost.getVersion())
                .ownerMemberId(jobPost.getOwnerId())
                .categoryId(jobPost.getCategoryId())
                .workDate(jobPost.getWorkDate())
                .startTime(jobPost.getStartTime())
                .endTime(jobPost.getEndTime())
                .latitude(jobPost.getLatitude())
                .longitude(jobPost.getLongitude())
                .admittedAt(expiresAt.minusMinutes(5))
                .expiresAt(expiresAt)
                .build());
    }
}
