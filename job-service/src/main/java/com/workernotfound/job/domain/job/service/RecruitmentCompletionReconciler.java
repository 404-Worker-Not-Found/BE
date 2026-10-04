package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import java.util.EnumSet;
import java.util.List;
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

    public int reconcile() {
        List<Long> jobPostIds = jobPostRepository.findFilledIdsByStatusIn(
                RECRUITING_STATUSES, PageRequest.of(0, properties.batchSize()));
        int completed = 0;
        for (Long jobPostId : jobPostIds) {
            if (completeSafely(jobPostId)) {
                completed++;
            }
        }
        return completed;
    }

    // 한 공고의 실패가 다른 공고 복구를 막지 않게 한다. 남은 공고는 다음 실행에서 다시 찾는다.
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
