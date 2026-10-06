package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지원 마감과 근무 시작 시각이 지난 공고의 상태를 바꾼다.
 *
 * <ul>
 *     <li>OPEN·MATCHING + 근무 시작 경과 → CLOSED({@code SYSTEM:WORK_STARTED}), matching-service에 모집 종료를 알린다.</li>
 *     <li>OPEN + 지원 마감 경과 → MATCHING({@code SYSTEM:APPLICATION_DEADLINE_PASSED}). 알리지 않는다. 알리면 matching-service가
 *     기존 지원자를 종료시키지만, MATCHING에서도 기존 지원자의 매칭은 계속되어야 한다.</li>
 *     <li>PAYMENT_PENDING + 지원 마감 경과 → CLOSED({@code SYSTEM:APPLICATION_DEADLINE_PASSED}). 공개된 적이 없어 지원자가 없으므로
 *     알리지 않는다.</li>
 * </ul>
 *
 * <p>공고 행 잠금을 얻은 뒤 {@link Clock}에서 현재 시각을 읽어 다시 판단한다. 경계 시각과 같으면 지난 것으로 본다. 근무 시작이 지났으면
 * 지원 마감도 지났으므로 OPEN 공고는 MATCHING을 거치지 않고 바로 마감한다.
 */
@Service
@RequiredArgsConstructor
public class JobScheduleTransitionService {

    static final String APPLICATION_DEADLINE_PASSED_REASON = "SYSTEM:APPLICATION_DEADLINE_PASSED";
    static final String WORK_STARTED_REASON = "SYSTEM:WORK_STARTED";

    private final JobPostRepository jobPostRepository;
    private final JobStatusChangeRecorder statusChangeRecorder;
    private final Clock clock;

    @Transactional
    public boolean applyIfDue(Long jobPostId) {
        Optional<JobPost> jobPost = jobPostRepository.findByIdForUpdate(jobPostId);
        if (jobPost.isEmpty()) {
            return false;
        }
        return applyIfDue(jobPost.get(), LocalDateTime.now(clock));
    }

    private boolean applyIfDue(JobPost jobPost, LocalDateTime now) {
        JobStatus fromStatus = jobPost.getStatus();
        if (jobPost.isRecruiting() && hasWorkStarted(jobPost, now)) {
            jobPost.closeForWorkStart();
            statusChangeRecorder.recordRecruitmentEnd(jobPost, fromStatus, WORK_STARTED_REASON, now);
            return true;
        }
        if (!isApplicationDeadlinePassed(jobPost, now)) {
            return false;
        }
        if (fromStatus == JobStatus.OPEN) {
            jobPost.stopApplicationsAfterDeadline();
            statusChangeRecorder.record(jobPost, fromStatus, APPLICATION_DEADLINE_PASSED_REASON, now);
            return true;
        }
        if (fromStatus == JobStatus.PAYMENT_PENDING) {
            jobPost.closeUnpublishedAfterDeadline();
            statusChangeRecorder.record(jobPost, fromStatus, APPLICATION_DEADLINE_PASSED_REASON, now);
            return true;
        }
        return false;
    }

    private boolean hasWorkStarted(JobPost jobPost, LocalDateTime now) {
        return !jobPost.getWorkDate().atTime(jobPost.getStartTime()).isAfter(now);
    }

    private boolean isApplicationDeadlinePassed(JobPost jobPost, LocalDateTime now) {
        return !jobPost.getApplicationDeadline().isAfter(now);
    }
}
