package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobCloseRequest;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobCloseResult;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobCloseRequestRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 점주의 공고 수동 마감을 처리한다.
 *
 * <p>공고 행 잠금 아래에서 상태 전이, 상태 이력, matching-service 모집 종료 알림 명령, 요청 기록을 한 트랜잭션에 저장한다. 같은 공고의
 * 자리 예약·확정, 지원 승인, 예치 상태 수신, 자동 상태 전이와 같은 잠금으로 직렬화된다. 이미 마감된 공고는 바꾸지 않고
 * {@link JobCloseResult#ALREADY_CLOSED}로 성공한다. 점주가 원하는 "더 이상 모집하지 않음"은 이미 이뤄졌기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class JobCloseCommandService {

    static final String MANUAL_CLOSE_REASON = "OWNER:MANUAL_CLOSE";

    private final JobPostRepository jobPostRepository;
    private final JobCloseRequestRepository closeRequestRepository;
    private final JobStatusChangeRecorder statusChangeRecorder;
    private final Clock clock;

    @Transactional
    public JobCloseRequest close(Long jobPostId, Long ownerMemberId, String idempotencyKey) {
        JobPost jobPost = lockOwnedJob(jobPostId, ownerMemberId);
        // 업무 상태보다 먼저 확인한다. 같은 키·같은 요청은 공고가 이후 바뀌어도 처음 결과를 돌려준다.
        Optional<JobCloseRequest> existing = closeRequestRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), jobPostId, ownerMemberId);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        JobStatus previousStatus = jobPost.getStatus();
        JobCloseResult result = closeIfNotClosed(jobPost, now);
        return closeRequestRepository.save(JobCloseRequest.builder()
                .jobPostId(jobPostId)
                .ownerMemberId(ownerMemberId)
                .idempotencyKey(idempotencyKey)
                .result(result)
                .previousStatus(previousStatus)
                .processedAt(now)
                .build());
    }

    // 다른 회원의 공고는 존재 여부를 드러내지 않도록 없는 공고와 같은 404로 응답한다.
    private JobPost lockOwnedJob(Long jobPostId, Long ownerMemberId) {
        JobPost jobPost = jobPostRepository.findByIdForUpdate(jobPostId)
                .orElseThrow(() -> new BusinessException(JobErrorCode.JOB_NOT_FOUND));
        if (!jobPost.getOwnerId().equals(ownerMemberId)) {
            throw new BusinessException(JobErrorCode.JOB_NOT_FOUND);
        }
        return jobPost;
    }

    private JobCloseRequest replay(JobCloseRequest request, Long jobPostId, Long ownerMemberId) {
        if (!request.isSameRequest(jobPostId, ownerMemberId)) {
            throw new BusinessException(JobErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return request;
    }

    // 공개된 적 없는 결제 대기 공고에는 지원자가 없으므로 matching-service에 알리지 않는다.
    private JobCloseResult closeIfNotClosed(JobPost jobPost, LocalDateTime now) {
        JobStatus fromStatus = jobPost.getStatus();
        if (fromStatus == JobStatus.CLOSED) {
            return JobCloseResult.ALREADY_CLOSED;
        }
        jobPost.closeByOwner();
        if (fromStatus == JobStatus.PAYMENT_PENDING) {
            statusChangeRecorder.record(jobPost, fromStatus, MANUAL_CLOSE_REASON, now);
        } else {
            statusChangeRecorder.recordRecruitmentEnd(jobPost, fromStatus, MANUAL_CLOSE_REASON, now);
        }
        return JobCloseResult.CLOSED;
    }
}
