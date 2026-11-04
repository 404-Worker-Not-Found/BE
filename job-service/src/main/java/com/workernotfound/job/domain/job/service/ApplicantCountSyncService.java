package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.port.ApplicationStateSnapshot;
import com.workernotfound.job.domain.job.port.ApplicationStateSnapshotReader;
import com.workernotfound.job.domain.job.repository.JobApplicantCountStateRepository;
import com.workernotfound.job.domain.job.service.ApplicantCountProjectionService.SnapshotApplyResult;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * matching-service 공고별 지원 상태 스냅샷으로 지원자 수 projection을 초기 적재하고 원본과 비교·재구축한다.
 *
 * <p>대상은 초기 적재 전인 공고와, 마지막 비교 뒤 이벤트로 바뀐 공고다. 스냅샷을 revision 비교로 반영하는 것이 곧 비교이자 재구축이다.
 * 이미 적재된 공고에서 바뀐 지원이 있으면 이벤트 반영이 원본과 어긋났던 것이므로 경고 로그를 남긴다. 스냅샷 조회는 트랜잭션 밖에서
 * 하고, 반영은 공고마다 별도 트랜잭션이다.
 *
 * <p>실행마다 직전 배치의 마지막 ID 다음부터 조회하고, 끝에 닿으면 처음부터 다시 찾는다. 계속 실패하는 공고가 낮은 ID에 있어도
 * 다른 공고의 적재를 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ApplicantCountSyncProperties.class)
public class ApplicantCountSyncService {

    private final JobApplicantCountStateRepository stateRepository;
    private final ApplicationStateSnapshotReader snapshotReader;
    private final ApplicantCountProjectionService projectionService;
    private final ApplicantCountSyncProperties properties;
    private final Clock clock;
    private final AtomicLong lastJobPostId = new AtomicLong();

    // 반영에 성공한 공고 수를 돌려준다.
    public int sync() {
        List<Long> jobPostIds = stateRepository.findSyncRequiredJobPostIdsByIdAfter(
                lastJobPostId.get(), PageRequest.of(0, properties.batchSize()));
        lastJobPostId.set(nextStartAfter(jobPostIds));
        int synced = 0;
        for (Long jobPostId : jobPostIds) {
            if (syncSafely(jobPostId)) {
                synced++;
            }
        }
        return synced;
    }

    // 배치가 덜 찼으면 끝까지 본 것이므로 다음 실행은 처음으로 돌아간다.
    private long nextStartAfter(List<Long> jobPostIds) {
        if (jobPostIds.size() < properties.batchSize()) {
            return 0;
        }
        return jobPostIds.get(jobPostIds.size() - 1);
    }

    private boolean syncSafely(Long jobPostId) {
        try {
            syncJob(jobPostId);
            return true;
        } catch (RuntimeException exception) {
            log.warn("지원자 수 projection 적재 실패: jobPostId={}, type={}", jobPostId, exception.getClass().getName());
            return false;
        }
    }

    // 조회 시작 시각을 적재 시각으로 남긴다. 조회 중 반영된 이벤트는 그보다 늦게 기록되어 다음 비교 대상이 된다.
    private void syncJob(Long jobPostId) {
        LocalDateTime fetchedAt = LocalDateTime.now(clock);
        List<ApplicationStateSnapshot> snapshots = snapshotReader.readApplicationStates(jobPostId);
        SnapshotApplyResult result = projectionService.applySnapshot(jobPostId, snapshots, fetchedAt);
        if (result.hasDrift()) {
            log.warn("지원자 수 projection을 원본 스냅샷으로 재구축했습니다: jobPostId={}, changedApplications={}",
                    jobPostId, result.changedCount());
        }
    }
}
