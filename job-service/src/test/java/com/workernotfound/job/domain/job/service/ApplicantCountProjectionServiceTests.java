package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobApplicationProjection;
import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;
import com.workernotfound.job.domain.job.event.ApplicationStatusChangedEvent;
import com.workernotfound.job.domain.job.exception.InvalidApplicationStateException;
import com.workernotfound.job.domain.job.port.ApplicationStateSnapshot;
import com.workernotfound.job.domain.job.repository.JobApplicantCountStateRepository;
import com.workernotfound.job.domain.job.repository.JobApplicationProjectionRepository;
import com.workernotfound.job.domain.job.service.ApplicantCountProjectionService.SnapshotApplyResult;
import com.workernotfound.job.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 지원별 revision 비교로 중복·역순 이벤트와 스냅샷을 반영하고, 초기 적재 전 공고는 지원자 수를 보여 주지 않는다.
class ApplicantCountProjectionServiceTests extends IntegrationTestSupport {

    private static final AtomicLong JOB_POST_IDS = new AtomicLong(9_160_000);
    private static final AtomicLong APPLICATION_IDS = new AtomicLong(9_160_000);
    private static final LocalDateTime FETCHED_AT = LocalDateTime.of(2026, 11, 3, 10, 0);

    @Autowired
    private ApplicantCountProjectionService projectionService;

    @Autowired
    private ApplicantCountFindService findService;

    @Autowired
    private JobApplicationProjectionRepository projectionRepository;

    @Autowired
    private JobApplicantCountStateRepository stateRepository;

    @Test
    void appliesNewerRevisionAndCountsOnlyAppliedApplications() {
        long jobPostId = synchronizedEmptyJob();
        long canceled = APPLICATION_IDS.incrementAndGet();
        long selected = APPLICATION_IDS.incrementAndGet();
        long applied = APPLICATION_IDS.incrementAndGet();

        apply(event(jobPostId, canceled, ApplicationStatus.APPLIED, 1));
        apply(event(jobPostId, selected, ApplicationStatus.APPLIED, 1));
        apply(event(jobPostId, applied, ApplicationStatus.APPLIED, 1));
        assertThat(findService.findApplicantCount(jobPostId)).isEqualTo(3);

        assertThat(apply(event(jobPostId, canceled, ApplicationStatus.CANCELED, 2))).isTrue();
        assertThat(apply(event(jobPostId, selected, ApplicationStatus.SELECTED, 2))).isTrue();

        assertThat(findService.findApplicantCount(jobPostId)).isEqualTo(1);
        assertThat(stored(canceled).getStatus()).isEqualTo(ApplicationStatus.CANCELED);
        assertThat(stored(canceled).getRevision()).isEqualTo(2);
    }

    @Test
    void redeliveryOfSameRevisionChangesNothing() {
        long jobPostId = synchronizedEmptyJob();
        long applicationId = APPLICATION_IDS.incrementAndGet();
        ApplicationStatusChangedEvent submitted = event(jobPostId, applicationId, ApplicationStatus.APPLIED, 1);
        apply(submitted);

        assertThat(apply(submitted)).isFalse();

        assertThat(stored(applicationId).getEventId()).isEqualTo(submitted.eventId());
        assertThat(findService.findApplicantCount(jobPostId)).isEqualTo(1);
    }

    // 취소가 먼저 처리되면 뒤늦게 온 최초 지원(revision 1)이 최신 상태와 집계를 덮어쓰지 않는다.
    @Test
    void lateOlderRevisionDoesNotOverwriteNewerState() {
        long jobPostId = synchronizedEmptyJob();
        long applicationId = APPLICATION_IDS.incrementAndGet();

        assertThat(apply(event(jobPostId, applicationId, ApplicationStatus.CANCELED, 2))).isTrue();
        assertThat(apply(event(jobPostId, applicationId, ApplicationStatus.APPLIED, 1))).isFalse();

        assertThat(stored(applicationId).getStatus()).isEqualTo(ApplicationStatus.CANCELED);
        assertThat(findService.findApplicantCount(jobPostId)).isZero();
    }

    @Test
    void sameRevisionWithDifferentStatusIsRefusedWithoutChange() {
        long jobPostId = synchronizedEmptyJob();
        long applicationId = APPLICATION_IDS.incrementAndGet();
        apply(event(jobPostId, applicationId, ApplicationStatus.APPLIED, 1));

        assertThatThrownBy(() -> apply(event(jobPostId, applicationId, ApplicationStatus.CANCELED, 1)))
                .isInstanceOf(InvalidApplicationStateException.class);

        assertThat(stored(applicationId).getStatus()).isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    void stateForAnotherJobPostIsRefused() {
        long jobPostId = synchronizedEmptyJob();
        long applicationId = APPLICATION_IDS.incrementAndGet();
        apply(event(jobPostId, applicationId, ApplicationStatus.APPLIED, 1));

        assertThatThrownBy(() -> apply(event(jobPostId + 1, applicationId, ApplicationStatus.CANCELED, 2)))
                .isInstanceOf(InvalidApplicationStateException.class);

        assertThat(stored(applicationId).getJobPostId()).isEqualTo(jobPostId);
        assertThat(stored(applicationId).getStatus()).isEqualTo(ApplicationStatus.APPLIED);
    }

    // 이벤트가 먼저 반영돼도 초기 적재 전에는 부분 집계를 보여 주지 않는다.
    @Test
    void hidesCountUntilInitialSnapshotIsApplied() {
        long jobPostId = JOB_POST_IDS.incrementAndGet();
        long fromEvent = APPLICATION_IDS.incrementAndGet();
        long fromSnapshot = APPLICATION_IDS.incrementAndGet();
        apply(event(jobPostId, fromEvent, ApplicationStatus.APPLIED, 1));

        assertThat(findService.findApplicantCount(jobPostId)).isNull();

        SnapshotApplyResult result = projectionService.applySnapshot(jobPostId, List.of(
                new ApplicationStateSnapshot(fromEvent, ApplicationStatus.APPLIED, 1L),
                new ApplicationStateSnapshot(fromSnapshot, ApplicationStatus.APPLIED, 1L)), FETCHED_AT);

        assertThat(result.wasSynced()).isFalse();
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(result.hasDrift()).isFalse();
        assertThat(findService.findApplicantCount(jobPostId)).isEqualTo(2);
        assertThat(stored(fromSnapshot).getEventId()).isNull();
    }

    // 스냅샷 조회 뒤 반영된 이벤트(더 큰 revision)는 늦게 커밋되는 스냅샷이 되돌리지 않는다.
    @Test
    void snapshotDoesNotDowngradeNewerEventState() {
        long jobPostId = JOB_POST_IDS.incrementAndGet();
        long applicationId = APPLICATION_IDS.incrementAndGet();
        apply(event(jobPostId, applicationId, ApplicationStatus.CANCELED, 2));

        projectionService.applySnapshot(jobPostId, List.of(
                new ApplicationStateSnapshot(applicationId, ApplicationStatus.APPLIED, 1L)), FETCHED_AT);

        assertThat(stored(applicationId).getStatus()).isEqualTo(ApplicationStatus.CANCELED);
        assertThat(findService.findApplicantCount(jobPostId)).isZero();
    }

    // 적재된 공고에서 놓친 이벤트를 스냅샷이 복구하면 원본과 어긋났던 것으로 알린다.
    @Test
    void snapshotRepairsMissedEventOnSynchronizedJobAndReportsDrift() {
        long jobPostId = synchronizedEmptyJob();
        long applicationId = APPLICATION_IDS.incrementAndGet();
        apply(event(jobPostId, applicationId, ApplicationStatus.APPLIED, 1));

        SnapshotApplyResult result = projectionService.applySnapshot(jobPostId, List.of(
                new ApplicationStateSnapshot(applicationId, ApplicationStatus.REJECTED, 2L)), FETCHED_AT.plusMinutes(1));

        assertThat(result.hasDrift()).isTrue();
        assertThat(findService.findApplicantCount(jobPostId)).isZero();
    }

    @Test
    void snapshotWithSameRevisionButDifferentStatusIsRefusedAndKeepsJobUnsynced() {
        long jobPostId = JOB_POST_IDS.incrementAndGet();
        long applicationId = APPLICATION_IDS.incrementAndGet();
        apply(event(jobPostId, applicationId, ApplicationStatus.APPLIED, 1));

        assertThatThrownBy(() -> projectionService.applySnapshot(jobPostId, List.of(
                new ApplicationStateSnapshot(applicationId, ApplicationStatus.REJECTED, 1L)), FETCHED_AT))
                .isInstanceOf(InvalidApplicationStateException.class);

        assertThat(findService.findApplicantCount(jobPostId)).isNull();
    }

    // 이벤트 반영은 비교 대상으로 표시하고, 그 뒤의 스냅샷 반영이 표시를 지운다.
    @Test
    void eventChangeMarksJobForComparisonUntilNextSnapshot() {
        long jobPostId = synchronizedEmptyJob();
        apply(event(jobPostId, APPLICATION_IDS.incrementAndGet(), ApplicationStatus.APPLIED, 1));
        var state = stateRepository.findById(jobPostId).orElseThrow();
        assertThat(state.getChangedAt()).isAfter(state.getSyncedAt());

        projectionService.applySnapshot(jobPostId, List.of(), state.getChangedAt().plusSeconds(1));

        var compared = stateRepository.findById(jobPostId).orElseThrow();
        assertThat(compared.getChangedAt()).isBefore(compared.getSyncedAt());
    }

    @Test
    void findsCountsOnlyForSynchronizedJobs() {
        long synced = synchronizedEmptyJob();
        long empty = synchronizedEmptyJob();
        long unsynced = JOB_POST_IDS.incrementAndGet();
        apply(event(synced, APPLICATION_IDS.incrementAndGet(), ApplicationStatus.APPLIED, 1));
        apply(event(unsynced, APPLICATION_IDS.incrementAndGet(), ApplicationStatus.APPLIED, 1));

        assertThat(findService.findApplicantCounts(List.of(synced, empty, unsynced)))
                .containsOnlyKeys(synced, empty)
                .containsEntry(synced, 1)
                .containsEntry(empty, 0);
    }

    private long synchronizedEmptyJob() {
        long jobPostId = JOB_POST_IDS.incrementAndGet();
        // 이벤트 반영 시각(현재)보다 이전에 적재된 공고로 둔다.
        projectionService.applySnapshot(jobPostId, List.of(), FETCHED_AT.minusYears(1));
        return jobPostId;
    }

    private boolean apply(ApplicationStatusChangedEvent event) {
        return projectionService.apply(event);
    }

    private JobApplicationProjection stored(long applicationId) {
        return projectionRepository.findById(applicationId).orElseThrow();
    }

    private static ApplicationStatusChangedEvent event(
            long jobPostId,
            long applicationId,
            ApplicationStatus status,
            long revision
    ) {
        return new ApplicationStatusChangedEvent(UUID.randomUUID().toString(), applicationId, jobPostId, status,
                revision);
    }
}
