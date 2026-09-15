package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.ApplicationAdmissionStatus;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ApplicationAdmissionProperties.class)
public class JobApplicationAdmissionCommandService {

    private final JobApplicationAdmissionRepository jobApplicationAdmissionRepository;
    private final JobPostRepository jobPostRepository;
    private final ApplicationAdmissionProperties applicationAdmissionProperties;

    @Transactional
    public JobApplicationAdmission create(Long jobPostId, Long workerMemberId, String idempotencyKey) {
        Optional<JobApplicationAdmission> existing =
                jobApplicationAdmissionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return reuseExisting(existing.get());
        }

        JobPost jobPost = jobPostRepository.findByIdForUpdate(jobPostId)
                .orElseThrow(() -> new BusinessException(JobErrorCode.JOB_NOT_FOUND));

        validateOpen(jobPost);
        validateApplicationDeadline(jobPost);

        return jobApplicationAdmissionRepository.save(newAdmission(jobPost, workerMemberId, idempotencyKey));
    }

    private JobApplicationAdmission reuseExisting(JobApplicationAdmission admission) {
        boolean stillValid = admission.getStatus() == ApplicationAdmissionStatus.RESERVED
                && admission.getExpiresAt().isAfter(LocalDateTime.now());
        if (!stillValid) {
            throw new BusinessException(JobErrorCode.ADMISSION_EXPIRED);
        }
        return admission;
    }

    private JobApplicationAdmission newAdmission(JobPost jobPost, Long workerMemberId, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now();
        return JobApplicationAdmission.builder()
                .jobPostId(jobPost.getId())
                .workerMemberId(workerMemberId)
                .idempotencyKey(idempotencyKey)
                .jobVersion(jobPost.getVersion())
                .admittedAt(now)
                .expiresAt(now.plus(applicationAdmissionProperties.ttl()))
                .build();
    }

    private void validateOpen(JobPost jobPost) {
        if (jobPost.getStatus() != JobStatus.OPEN) {
            throw new BusinessException(JobErrorCode.JOB_NOT_OPEN);
        }
    }

    private void validateApplicationDeadline(JobPost jobPost) {
        if (!jobPost.getApplicationDeadline().isAfter(LocalDateTime.now())) {
            throw new BusinessException(JobErrorCode.APPLICATION_DEADLINE_PASSED);
        }
    }
}
