package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 확정 인원이 모집 인원에 도달했는데 OPEN·MATCHING으로 남은 공고를 모집 완료로 마감한다.
 *
 * <p>모집 완료 구현 이전에 마지막 자리가 확정된 공고를 위한 복구 경로다. 대상은 {@code CONSUMED 수 >= recruitCount}인
 * 모집 중 공고뿐이며, 공고마다 별도 트랜잭션에서 공고 행을 잠그고 같은 기준으로 다시 판단한다. 마감된 공고는 일반 경로와
 * 같은 상태 이력과 알림 명령을 남기므로 알림 전송도 같은 실행기가 맡는다.
 *
 * <p>실행마다 직전 배치의 마지막 ID 다음부터 조회하고, 끝에 닿으면 다음 실행은 처음부터 다시 찾는다. 계속 실패하는 공고가
 * 낮은 ID에 있어도 매 배치를 차지하지 않으며, 실패한 공고는 한 바퀴를 돈 뒤 다시 시도한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(RecruitmentCompletionReconcileProperties.class)
public class RecruitmentCompletionReconciler {

    private static final EnumSet<JobStatus> RECRUITING_STATUSES = EnumSet.of(JobStatus.OPEN, JobStatus.MATCHING);

    private final JobPostRepository jobPostRepository;
    private final JobRecruitmentCompletionService completionService;
    private final RecruitmentCompletionReconcileProperties properties;
    private final AtomicLong lastJobPostId = new AtomicLong();

    public int reconcile() {
        List<Long> jobPostIds = jobPostRepository.findFilledIdsByStatusInAfter(
                RECRUITING_STATUSES, lastJobPostId.get(), PageRequest.of(0, properties.batchSize()));
        lastJobPostId.set(nextStartAfter(jobPostIds));
        int completed = 0;
        for (Long jobPostId : jobPostIds) {
            if (completeSafely(jobPostId)) {
                completed++;
            }
        }
        return completed;
    }

    // 성공 여부와 관계없이 조회한 위치를 넘긴다. 배치가 덜 찼으면 끝까지 본 것이므로 처음으로 돌아간다.
    private long nextStartAfter(List<Long> jobPostIds) {
        if (jobPostIds.size() < properties.batchSize()) {
            return 0;
        }
        return jobPostIds.get(jobPostIds.size() - 1);
    }

    // 한 공고의 실패가 다른 공고 복구를 막지 않게 한다. 실패한 공고는 조회 위치가 처음으로 돌아온 뒤 다시 찾는다.
    private boolean completeSafely(Long jobPostId) {
        try {
            boolean isCompleted = completionService.completeIfFilled(jobPostId);
            if (isCompleted) {
                log.info("정원이 확정된 공고를 모집 완료로 복구: jobPostId={}", jobPostId);
            }
            return isCompleted;
        } catch (RuntimeException exception) {
            log.warn("모집 완료 복구 실패: jobPostId={}, type={}", jobPostId, exception.getClass().getName());
            return false;
        }
    }
}
