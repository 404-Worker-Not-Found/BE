package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.dto.response.ApplicationAdmissionResponse;
import com.workernotfound.job.domain.job.dto.request.JobSearchRequest;
import com.workernotfound.job.domain.job.dto.response.JobDetailResponse;
import com.workernotfound.job.domain.job.dto.response.JobSearchResponse;
import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JobApplicationService {

    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "uk_job_application_admissions_idempotency_key";

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
        JobApplicationAdmission admission = createAdmission(jobPostId, workerMemberId, idempotencyKey);
        return ApplicationAdmissionResponse.of(admission, jobFindService.findJobPost(jobPostId));
    }

    private JobApplicationAdmission createAdmission(Long jobPostId, Long workerMemberId, String idempotencyKey) {
        try {
            return jobApplicationAdmissionCommandService.create(jobPostId, workerMemberId, idempotencyKey);
        } catch (RuntimeException exception) {
            // 저장 트랜잭션이 롤백된 뒤에 변환한다. 트랜잭션 안에서 바꾸면 롤백 표시와 영속성 컨텍스트 상태를 되돌릴 수 없다.
            throw translateAdmissionPersistenceFailure(exception);
        }
    }

    // 서로 다른 공고에 같은 멱등 키를 동시에 쓰는 요청은 공고 행 잠금으로 직렬화되지 않아 유일 제약에서만 걸러진다.
    // 커밋 시점 실패까지 담으려면 예외가 몇 겹으로 감싸졌는지 알 수 없으므로 cause 사슬 전체를 확인한다.
    private RuntimeException translateAdmissionPersistenceFailure(RuntimeException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof ConstraintViolationException violation)) continue;
            if (!isIdempotencyKeyConstraint(violation.getConstraintName())) return exception;
            BusinessException conflict = new BusinessException(JobErrorCode.IDEMPOTENCY_KEY_REUSED);
            conflict.initCause(exception);
            return conflict;
        }
        return exception;
    }

    // MySQL은 제약 이름을 `테이블명.제약명` 형태로 보고한다.
    private boolean isIdempotencyKeyConstraint(String constraintName) {
        if (constraintName == null) return false;
        String name = constraintName.substring(constraintName.lastIndexOf('.') + 1);
        return IDEMPOTENCY_KEY_CONSTRAINT.equalsIgnoreCase(name);
    }
}
