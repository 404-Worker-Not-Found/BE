package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 지원 마감·근무 시작이 지난 공고를 찾아 공고마다 {@link JobScheduleTransitionService}로 상태를 바꾼다.
 *
 * <p>조회는 잠그지 않고 후보 ID만 고른다. 공고마다 별도 트랜잭션에서 공고 행을 잠근 뒤 현재 시각으로 다시 판단하므로, 다른 경로가 먼저
 * 바꾼 공고나 반복·동시 실행에서도 전이·이력·알림 명령은 한 번만 남는다. 지원 마감 후보와 근무 시작 후보는 각자의 조회 위치를 두고,
 * 실행마다 직전 배치의 마지막 ID 다음부터 찾는다. 배치가 덜 차면 끝에 닿은 것으로 보고 다음 실행은 처음부터 찾는다. 처리에 실패한
 * 공고도 조회 위치를 넘기므로 계속 실패하는 낮은 ID 공고가 매 배치를 차지하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(JobScheduleTransitionProperties.class)
public class JobScheduleTransitionReconciler {

    private static final EnumSet<JobStatus> DEADLINE_TARGET_STATUSES =
            EnumSet.of(JobStatus.PAYMENT_PENDING, JobStatus.OPEN);
    private static final EnumSet<JobStatus> WORK_START_TARGET_STATUSES = EnumSet.of(JobStatus.OPEN, JobStatus.MATCHING);

    private final JobPostRepository jobPostRepository;
    private final JobScheduleTransitionService transitionService;
    private final JobScheduleTransitionProperties properties;
    private final Clock clock;
    private final AtomicLong lastDeadlineJobPostId = new AtomicLong();
    private final AtomicLong lastWorkStartJobPostId = new AtomicLong();

    public int reconcile() {
        // 지원 마감은 DATETIME(6)이다. 인자를 절삭해야 MySQL이 나노초를 반올림해 경계를 앞당기지 않는다.
        // 근무 시작 시각은 초 단위 TIME이므로 초로 절삭한다. 후보 조회일 뿐 최종 판단은 잠금 뒤 절삭하지 않은 시각으로 한다.
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> workStartedIds = jobPostRepository.findWorkStartedIdsByStatusInAfter(
                WORK_START_TARGET_STATUSES,
                now.toLocalDate(),
                now.toLocalTime().truncatedTo(ChronoUnit.SECONDS),
                lastWorkStartJobPostId.get(),
                PageRequest.of(0, properties.batchSize()));
        lastWorkStartJobPostId.set(nextStartAfter(workStartedIds));
        List<Long> deadlinePassedIds = jobPostRepository.findDeadlinePassedIdsByStatusInAfter(
                DEADLINE_TARGET_STATUSES,
                now.truncatedTo(ChronoUnit.MICROS),
                lastDeadlineJobPostId.get(),
                PageRequest.of(0, properties.batchSize()));
        lastDeadlineJobPostId.set(nextStartAfter(deadlinePassedIds));
        return applyEach(workStartedIds) + applyEach(deadlinePassedIds);
    }

    private int applyEach(List<Long> jobPostIds) {
        int changed = 0;
        for (Long jobPostId : jobPostIds) {
            if (applySafely(jobPostId)) {
                changed++;
            }
        }
        return changed;
    }

    // 성공 여부와 관계없이 조회한 위치를 넘긴다. 배치가 덜 찼으면 끝까지 본 것이므로 처음으로 돌아간다.
    private long nextStartAfter(List<Long> jobPostIds) {
        if (jobPostIds.size() < properties.batchSize()) {
            return 0;
        }
        return jobPostIds.get(jobPostIds.size() - 1);
    }

    // 한 공고의 실패가 다른 공고의 전이를 막지 않게 한다. 실패한 공고는 조회 위치가 처음으로 돌아온 뒤 다시 찾는다.
    private boolean applySafely(Long jobPostId) {
        try {
            return transitionService.applyIfDue(jobPostId);
        } catch (RuntimeException exception) {
            log.warn("공고 자동 상태 전이 실패: jobPostId={}, type={}", jobPostId, exception.getClass().getName());
            return false;
        }
    }
}
