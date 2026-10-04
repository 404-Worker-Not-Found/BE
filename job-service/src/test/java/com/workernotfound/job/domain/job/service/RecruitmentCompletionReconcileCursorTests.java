package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.repository.JobPostRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 계속 실패하는 낮은 ID 공고가 매 배치를 차지하지 않고, 뒤의 공고도 복구되는지 확인한다.
class RecruitmentCompletionReconcileCursorTests {

    private static final long FAILING_ID = 1L;

    private final TreeSet<Long> filledIds = new TreeSet<>(List.of(FAILING_ID, 2L, 3L));
    private final List<Long> attemptedIds = new ArrayList<>();
    private final JobPostRepository jobPostRepository = mock(JobPostRepository.class);
    private final JobRecruitmentCompletionService completionService = mock(JobRecruitmentCompletionService.class);

    @BeforeEach
    void setUp() {
        when(jobPostRepository.findFilledIdsByStatusInAfter(any(), anyLong(), any(Pageable.class)))
                .thenAnswer(invocation -> filledIdsAfter(invocation.getArgument(1), invocation.getArgument(2)));
        when(completionService.completeIfFilled(anyLong())).thenAnswer(invocation -> {
            Long jobPostId = invocation.getArgument(0);
            attemptedIds.add(jobPostId);
            if (jobPostId == FAILING_ID) {
                throw new IllegalStateException("persistent failure");
            }
            return filledIds.remove(jobPostId);
        });
    }

    @Test
    void laterJobsAreCompletedWhileLowestJobKeepsFailing() {
        RecruitmentCompletionReconciler reconciler = reconciler(1);

        List<Integer> completedPerRun = List.of(
                reconciler.reconcile(), reconciler.reconcile(), reconciler.reconcile());

        assertThat(attemptedIds).containsExactly(FAILING_ID, 2L, 3L);
        assertThat(completedPerRun).containsExactly(0, 1, 1);
        assertThat(filledIds).containsExactly(FAILING_ID);
    }

    @Test
    void failedJobIsRetriedAfterReachingTheEnd() {
        RecruitmentCompletionReconciler reconciler = reconciler(2);

        reconciler.reconcile();
        reconciler.reconcile();
        reconciler.reconcile();

        // 첫 배치 [1, 2], 다음 배치 [3]에서 끝에 닿아 처음으로 돌아가고, 남은 [1]을 다시 시도한다.
        assertThat(attemptedIds).containsExactly(FAILING_ID, 2L, 3L, FAILING_ID);
    }

    private List<Long> filledIdsAfter(Long afterId, Pageable pageable) {
        return filledIds.tailSet(afterId, false).stream()
                .limit(pageable.getPageSize())
                .toList();
    }

    private RecruitmentCompletionReconciler reconciler(int batchSize) {
        return new RecruitmentCompletionReconciler(
                jobPostRepository,
                completionService,
                new RecruitmentCompletionReconcileProperties(Duration.ofMinutes(10), Duration.ZERO, batchSize));
    }
}
