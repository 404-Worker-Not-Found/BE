package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobApplicantCountState;
import com.workernotfound.job.domain.job.entity.JobApplicationProjection;
import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;
import com.workernotfound.job.domain.job.event.ApplicationStatusChangedEvent;
import com.workernotfound.job.domain.job.port.ApplicationStateSnapshot;
import com.workernotfound.job.domain.job.repository.JobApplicantCountStateRepository;
import com.workernotfound.job.domain.job.repository.JobApplicationProjectionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * matching-service 지원 상태 이벤트와 공고별 지원 상태 스냅샷을 지원자 수 projection에 반영한다.
 *
 * <p>두 입력 모두 공고별 적재 상태 행을 먼저 잠근 트랜잭션에서 지원별 revision 비교로 반영한다. 같은 공고의 이벤트·스냅샷 반영이
 * 직렬화되므로 지원 행이 없을 때 동시에 만들지 않고, 처리 순서와 관계없이 지원별로 가장 큰 revision의 상태로 수렴한다.
 * 계약 위반({@link com.workernotfound.job.domain.job.exception.InvalidApplicationStateException})과 DB 오류는 전체를 롤백한다.
 */
@Service
@RequiredArgsConstructor
public class ApplicantCountProjectionService {

    private final JobApplicantCountStateRepository stateRepository;
    private final JobApplicationProjectionRepository projectionRepository;
    private final Clock clock;

    // 이벤트로 projection이 바뀌었으면 true다. 같은 revision의 재전달과 더 작은 revision은 바꾸지 않는다.
    @Transactional
    public boolean apply(ApplicationStatusChangedEvent event) {
        LocalDateTime now = now();
        JobApplicantCountState state = lockState(event.jobPostId(), now);
        boolean isChanged = applyState(event.jobPostId(), event.applicationId(), event.status(), event.revision(),
                event.eventId());
        if (isChanged) {
            state.recordChange(now);
        }
        return isChanged;
    }

    /**
     * {@code fetchedAt}에 조회를 시작한 스냅샷을 반영하고 적재 시각을 기록한다. 스냅샷보다 큰 revision이 이미 반영된 지원은 그대로 둔다.
     */
    @Transactional
    public SnapshotApplyResult applySnapshot(
            Long jobPostId,
            List<ApplicationStateSnapshot> snapshots,
            LocalDateTime fetchedAt
    ) {
        JobApplicantCountState state = lockState(jobPostId, now());
        boolean wasSynced = state.isSynced();
        int changedCount = 0;
        for (ApplicationStateSnapshot snapshot : snapshots) {
            if (applyState(jobPostId, snapshot.applicationId(), snapshot.status(), snapshot.revision(), null)) {
                changedCount++;
            }
        }
        state.recordSync(fetchedAt.truncatedTo(ChronoUnit.MICROS));
        return new SnapshotApplyResult(wasSynced, changedCount);
    }

    private JobApplicantCountState lockState(Long jobPostId, LocalDateTime now) {
        stateRepository.ensureExists(jobPostId, now);
        return stateRepository.findByJobPostIdForUpdate(jobPostId).orElseThrow();
    }

    private boolean applyState(
            Long jobPostId,
            Long applicationId,
            ApplicationStatus status,
            Long revision,
            String eventId
    ) {
        Optional<JobApplicationProjection> projection = projectionRepository.findById(applicationId);
        if (projection.isPresent()) {
            return projection.get().change(jobPostId, status, revision, eventId);
        }
        projectionRepository.save(JobApplicationProjection.builder()
                .applicationId(applicationId)
                .jobPostId(jobPostId)
                .status(status)
                .revision(revision)
                .eventId(eventId)
                .build());
        return true;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    // wasSynced: 반영 전에 초기 적재가 끝나 있었는지. 그때 바뀐 지원이 있으면 이벤트 반영이 원본과 어긋났던 것이다.
    public record SnapshotApplyResult(boolean wasSynced, int changedCount) {

        public boolean hasDrift() {
            return wasSynced && changedCount > 0;
        }
    }
}
