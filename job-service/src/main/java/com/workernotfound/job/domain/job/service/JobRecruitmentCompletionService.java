package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.JobStatusHistory;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.JobStatusHistoryRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확정 인원이 모집 인원에 도달한 공고를 모집 완료로 마감한다.
 *
 * <p>모집 완료 기준은 {@code CONSUMED 수 >= recruitCount}다. 유효한 RESERVED는 추가 예약만 막을 뿐 완료 기준에 넣지 않는다.
 * 공고 상태 전이, 상태 이력, 모집 완료 알림 명령은 호출자의 트랜잭션에서 함께 저장되어 함께 커밋되거나 함께 롤백된다.
 */
@Service
@RequiredArgsConstructor
public class JobRecruitmentCompletionService {

    static final String RECRUITMENT_FILLED_REASON = "SYSTEM:RECRUITMENT_FILLED";

    private final JobPostRepository jobPostRepository;
    private final JobMatchingSeatReservationRepository reservationRepository;
    private final JobStatusHistoryRepository historyRepository;
    private final RecruitmentCompletionCommandRepository commandRepository;

    /**
     * 호출자는 같은 트랜잭션에서 이 공고 행의 비관적 잠금을 이미 잡고 있어야 한다. 같은 공고의 확정과 완료 판단이
     * 그 잠금으로 직렬화되므로 이미 마감된 공고에는 이력과 명령을 다시 만들지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RecruitmentCompletionCommand> completeIfFilled(JobPost lockedJobPost, LocalDateTime now) {
        if (!lockedJobPost.isRecruiting() || !isFilled(lockedJobPost)) {
            return Optional.empty();
        }
        JobStatus fromStatus = lockedJobPost.getStatus();
        lockedJobPost.closeForRecruitmentCompletion();
        // @Version은 flush 때 증가한다. 공고를 따로 커밋하지 않고 같은 트랜잭션에서 flush해 완료 전이의 버전을 확정한다.
        jobPostRepository.flush();
        LocalDateTime changedAt = RecruitmentCompletionCommand.toStoredTime(now);
        historyRepository.save(JobStatusHistory.builder()
                .jobPostId(lockedJobPost.getId())
                .fromStatus(fromStatus)
                .toStatus(JobStatus.CLOSED)
                .reason(RECRUITMENT_FILLED_REASON)
                .createdAt(changedAt)
                .build());
        return Optional.of(commandRepository.save(RecruitmentCompletionCommand.builder()
                .commandId(UUID.randomUUID().toString())
                .jobPostId(lockedJobPost.getId())
                .jobVersion(lockedJobPost.getVersion())
                .nextAttemptAt(changedAt)
                .build()));
    }

    private boolean isFilled(JobPost jobPost) {
        // 같은 트랜잭션에서 방금 CONSUMED로 바꾼 예약이 집계에 포함되도록 먼저 반영한다.
        reservationRepository.flush();
        long consumed = reservationRepository.countByJobPostIdAndStatusIn(
                jobPost.getId(), EnumSet.of(MatchingSeatReservationStatus.CONSUMED));
        return consumed >= jobPost.getRecruitCount();
    }
}
