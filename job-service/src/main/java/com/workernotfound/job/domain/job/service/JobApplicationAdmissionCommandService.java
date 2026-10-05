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

      private final com.workernotfound.job.global.account.AccountGateService accountGates;
  private final JobApplicationAdmissionRepository jobApplicationAdmissionRepository;
    private final JobPostRepository jobPostRepository;
    private final ApplicationAdmissionProperties applicationAdmissionProperties;

    @Transactional
    public JobApplicationAdmission create(Long jobPostId, Long workerMemberId, String idempotencyKey) {
        // 공고 행 잠금을 먼저 잡아야 같은 키의 동시 요청이 앞선 커밋 결과를 조회할 수 있다.
        JobPost jobPost = jobPostRepository.findByIdForUpdate(jobPostId)
                .orElseThrow(() -> new BusinessException(JobErrorCode.JOB_NOT_FOUND));
        accountGates.requireActive(jobPost.getOwnerId(), workerMemberId);

        Optional<JobApplicationAdmission> existing =
                jobApplicationAdmissionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return reuseExisting(existing.get(), jobPostId, workerMemberId);
        }

        validateOpen(jobPost);
        validateFundingNotBlocked(jobPost);
        validateApplicationDeadline(jobPost);

        return jobApplicationAdmissionRepository.save(newAdmission(jobPost, workerMemberId, idempotencyKey));
    }

    private JobApplicationAdmission reuseExisting(
            JobApplicationAdmission admission,
            Long jobPostId,
            Long workerMemberId
    ) {
        if (!isSameRequest(admission, jobPostId, workerMemberId)) {
            throw new BusinessException(JobErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        boolean stillValid = admission.getStatus() == ApplicationAdmissionStatus.RESERVED
                && admission.getExpiresAt().isAfter(LocalDateTime.now());
        if (!stillValid) {
            throw new BusinessException(JobErrorCode.ADMISSION_EXPIRED);
        }
        return admission;
    }

    private boolean isSameRequest(JobApplicationAdmission admission, Long jobPostId, Long workerMemberId) {
        return admission.getJobPostId().equals(jobPostId)
                && admission.getWorkerMemberId().equals(workerMemberId);
    }

    private JobApplicationAdmission newAdmission(JobPost jobPost, Long workerMemberId, String idempotencyKey) {
        LocalDateTime now = LocalDateTime.now();
        return JobApplicationAdmission.builder()
                .jobPostId(jobPost.getId())
                .workerMemberId(workerMemberId)
                .idempotencyKey(idempotencyKey)
                .jobVersion(jobPost.getVersion())
                .ownerMemberId(jobPost.getOwnerId())
                .categoryId(jobPost.getCategoryId())
                .workDate(jobPost.getWorkDate())
                .startTime(jobPost.getStartTime())
                .endTime(jobPost.getEndTime())
                .latitude(jobPost.getLatitude())
                .longitude(jobPost.getLongitude())
                .admittedAt(now)
                .expiresAt(now.plus(applicationAdmissionProperties.ttl()))
                .build();
    }

    private void validateOpen(JobPost jobPost) {
        if (jobPost.getStatus() != JobStatus.OPEN) {
            throw new BusinessException(JobErrorCode.JOB_NOT_OPEN);
        }
    }

    // OPEN 상태만으로 판단하지 않는다. 예치 취소·검토 필요가 확인된 공고는 상태와 관계없이 신규 지원을 받지 않는다.
    // matching-service가 이미 처리하는 "지원을 받지 않는 공고" 코드로 응답한다.
    private void validateFundingNotBlocked(JobPost jobPost) {
        if (jobPost.isFundingBlocked()) {
            throw new BusinessException(JobErrorCode.JOB_NOT_OPEN, "예치 확인이 필요해 신규 지원을 받지 않는 공고입니다.");
        }
    }

    private void validateApplicationDeadline(JobPost jobPost) {
        if (!jobPost.getApplicationDeadline().isAfter(LocalDateTime.now())) {
            throw new BusinessException(JobErrorCode.APPLICATION_DEADLINE_PASSED);
        }
    }
}
