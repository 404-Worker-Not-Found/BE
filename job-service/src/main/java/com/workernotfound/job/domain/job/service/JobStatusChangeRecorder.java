package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.JobStatusHistory;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.event.RecruitmentCompletionCommandCreatedEvent;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.JobStatusHistoryRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공고 상태 전이를 기록한다. 호출자는 같은 트랜잭션에서 공고 행 잠금을 잡고 상태를 이미 바꾼 뒤 호출한다.
 *
 * <p>JPA {@code @Version}은 flush 때 증가하므로 먼저 flush해 전이 후 버전을 확정한다. 공고를 따로 커밋하지 않는다.
 * 이력과 모집 종료 알림 명령은 호출자의 트랜잭션에서 상태 전이와 함께 커밋되거나 함께 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class JobStatusChangeRecorder {

    private final JobPostRepository jobPostRepository;
    private final JobStatusHistoryRepository historyRepository;
    private final RecruitmentCompletionCommandRepository commandRepository;
    private final ApplicationEventPublisher eventPublisher;

    // matching-service에 알리지 않는 전이다. 예: 지원 마감에 따른 OPEN → MATCHING, 결제 대기 공고의 마감.
    @Transactional(propagation = Propagation.MANDATORY)
    public JobStatusHistory record(JobPost lockedJobPost, JobStatus fromStatus, String reason, LocalDateTime now) {
        jobPostRepository.flush();
        return saveHistory(lockedJobPost, fromStatus, reason, RecruitmentCompletionCommand.toStoredTime(now));
    }

    /**
     * 모집이 끝난 전이(OPEN·MATCHING → CLOSED)를 기록하고, 전이 후 공고 버전으로 matching-service 모집 종료 알림 명령을 저장한다.
     * matching-service는 이 버전 이하의 늦은 지원을 차단하고 남은 지원과 매칭 제안을 종료한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RecruitmentCompletionCommand recordRecruitmentEnd(
            JobPost lockedJobPost,
            JobStatus fromStatus,
            String reason,
            LocalDateTime now
    ) {
        jobPostRepository.flush();
        LocalDateTime changedAt = RecruitmentCompletionCommand.toStoredTime(now);
        saveHistory(lockedJobPost, fromStatus, reason, changedAt);
        RecruitmentCompletionCommand command = commandRepository.save(RecruitmentCompletionCommand.builder()
                .commandId(UUID.randomUUID().toString())
                .jobPostId(lockedJobPost.getId())
                .jobVersion(lockedJobPost.getVersion())
                .nextAttemptAt(changedAt)
                .build());
        eventPublisher.publishEvent(new RecruitmentCompletionCommandCreatedEvent(command.getId()));
        return command;
    }

    private JobStatusHistory saveHistory(
            JobPost lockedJobPost,
            JobStatus fromStatus,
            String reason,
            LocalDateTime changedAt
    ) {
        return historyRepository.save(JobStatusHistory.builder()
                .jobPostId(lockedJobPost.getId())
                .fromStatus(fromStatus)
                .toStatus(lockedJobPost.getStatus())
                .reason(reason)
                .createdAt(changedAt)
                .build());
    }
}
