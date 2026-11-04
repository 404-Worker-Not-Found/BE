package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;
import com.workernotfound.job.domain.job.event.ApplicationStatusChangedEvent;
import com.workernotfound.job.domain.job.exception.ApplicationStateSnapshotException;
import com.workernotfound.job.domain.job.port.ApplicationStateSnapshot;
import com.workernotfound.job.domain.job.port.ApplicationStateSnapshotReader;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 초기 적재 전 공고와 이벤트로 바뀐 공고를 matching 스냅샷으로 적재·비교하고, 한 공고의 실패가 다른 공고를 막지 않는다.
@ExtendWith(OutputCaptureExtension.class)
class ApplicantCountSyncServiceTests extends IntegrationTestSupport {

    private static final AtomicLong APPLICATION_IDS = new AtomicLong(9_162_000);

    @MockitoBean
    private ApplicationStateSnapshotReader snapshotReader;

    @Autowired
    private ApplicantCountSyncService syncService;

    @Autowired
    private ApplicantCountProjectionService projectionService;

    @Autowired
    private ApplicantCountFindService findService;

    @Autowired
    private JobPostRepository jobPostRepository;

    // 다른 테스트 클래스가 남긴 공고까지 한 번에 훑도록 배치를 크게 둔다. 대역 리더는 그 공고들에 빈 스냅샷을 돌려준다.
    @DynamicPropertySource
    static void useLargeBatch(DynamicPropertyRegistry registry) {
        registry.add("job.applicant-count.sync.batch-size", () -> "100000");
    }

    @Test
    void initialSyncLoadsSnapshotAndShowsCount() {
        JobPost post = saveJob();
        when(snapshotReader.readApplicationStates(post.getId())).thenReturn(List.of(
                snapshot(ApplicationStatus.APPLIED, 1),
                snapshot(ApplicationStatus.APPLIED, 1),
                snapshot(ApplicationStatus.CANCELED, 2)));
        assertThat(findService.findApplicantCount(post.getId())).isNull();

        syncService.sync();

        assertThat(findService.findApplicantCount(post.getId())).isEqualTo(2);
    }

    @Test
    void failedSnapshotKeepsJobUnsyncedWithoutBlockingOthers() {
        JobPost failing = saveJob();
        JobPost succeeding = saveJob();
        when(snapshotReader.readApplicationStates(failing.getId()))
                .thenThrow(new ApplicationStateSnapshotException("연결 실패"));
        when(snapshotReader.readApplicationStates(succeeding.getId()))
                .thenReturn(List.of(snapshot(ApplicationStatus.APPLIED, 1)));

        syncService.sync();

        assertThat(findService.findApplicantCount(failing.getId())).isNull();
        assertThat(findService.findApplicantCount(succeeding.getId())).isEqualTo(1);
    }

    // 적재 뒤 바뀌지 않은 공고는 다시 읽지 않고, 이벤트로 바뀐 공고만 원본과 다시 비교한다.
    @Test
    void comparesOnlyJobsChangedSinceLastSync() {
        JobPost unchanged = saveJob();
        JobPost changed = saveJob();
        syncService.sync();
        projectionService.apply(new ApplicationStatusChangedEvent(UUID.randomUUID().toString(),
                APPLICATION_IDS.incrementAndGet(), changed.getId(), ApplicationStatus.APPLIED, 1L));
        clearInvocations(snapshotReader);

        syncService.sync();

        verify(snapshotReader, never()).readApplicationStates(unchanged.getId());
        verify(snapshotReader, times(1)).readApplicationStates(changed.getId());

        syncService.sync();

        verify(snapshotReader, times(1)).readApplicationStates(changed.getId());
    }

    // 놓친 이벤트를 원본 비교가 찾아 재구축하고 경고 로그로 알린다.
    @Test
    void rebuildsDriftedJobAndLogsWarning(CapturedOutput output) {
        JobPost post = saveJob();
        long applicationId = APPLICATION_IDS.incrementAndGet();
        syncService.sync();
        projectionService.apply(new ApplicationStatusChangedEvent(UUID.randomUUID().toString(), applicationId,
                post.getId(), ApplicationStatus.APPLIED, 1L));
        long missed = APPLICATION_IDS.incrementAndGet();
        when(snapshotReader.readApplicationStates(post.getId())).thenReturn(List.of(
                new ApplicationStateSnapshot(applicationId, ApplicationStatus.APPLIED, 1L),
                new ApplicationStateSnapshot(missed, ApplicationStatus.APPLIED, 1L)));

        syncService.sync();

        assertThat(findService.findApplicantCount(post.getId())).isEqualTo(2);
        assertThat(output).contains("지원자 수 projection을 원본 스냅샷으로 재구축했습니다")
                .contains("jobPostId=" + post.getId());
    }

    private JobPost saveJob() {
        return jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().build()));
    }

    private static ApplicationStateSnapshot snapshot(ApplicationStatus status, long revision) {
        return new ApplicationStateSnapshot(APPLICATION_IDS.incrementAndGet(), status, revision);
    }
}
