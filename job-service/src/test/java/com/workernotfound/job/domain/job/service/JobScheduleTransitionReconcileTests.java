package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.JobStatusHistoryRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 자동 상태 전이 스케줄러의 대상 조회, 반복·동시 실행의 1회 기록, 저장 실패 시 상태 롤백을 검증한다.
 *
 * <p>대상 조회는 공유 테스트 DB 전체를 보므로 다른 테스트가 남긴 지난 공고도 함께 전이된다. 배치 크기를 넘는 대상이 있어도 확인할 공고가
 * 처리되도록 더 바꿀 공고가 없을 때까지 실행한다.
 */
class JobScheduleTransitionReconcileTests extends IntegrationTestSupport {

    private final LocalDateTime workStart = LocalDate.now().plusDays(7).atTime(9, 0);
    private final LocalDateTime deadline = workStart.minusHours(2);

    @Autowired
    private JobScheduleTransitionReconciler reconciler;

    @Autowired
    private JobScheduleTransitionService transitionService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobStatusHistoryRepository historyRepository;

    @Autowired
    private RecruitmentCompletionCommandRepository commandRepository;

    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        clock.reset();
    }

    @Test
    void reconcileTransitionsOnlyDueJobs() {
        JobPost unpublished = saveJob(JobStatus.PAYMENT_PENDING, deadline, workStart);
        JobPost open = saveJob(JobStatus.OPEN, deadline, workStart);
        JobPost matchingStarted = saveJob(JobStatus.MATCHING, deadline.minusDays(1), workStart.minusDays(1));
        JobPost openNotDue = saveJob(JobStatus.OPEN, deadline.plusHours(1), workStart.plusHours(1));
        JobPost matchingNotStarted = saveJob(JobStatus.MATCHING, deadline.minusHours(1), workStart);
        JobPost closed = saveJob(JobStatus.CLOSED, deadline.minusDays(1), workStart.minusDays(1));
        fixAt(deadline);

        reconcileUntilIdle();

        assertThat(reload(unpublished).getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(reload(open).getStatus()).isEqualTo(JobStatus.MATCHING);
        assertThat(reload(matchingStarted).getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(commandRepository.findByJobPostId(unpublished.getId())).isEmpty();
        assertThat(commandRepository.findByJobPostId(open.getId())).isEmpty();
        assertThat(singleCommand(matchingStarted).getJobVersion()).isEqualTo(reload(matchingStarted).getVersion());
        for (JobPost untouched : List.of(openNotDue, matchingNotStarted, closed)) {
            assertThat(reload(untouched).getStatus()).isEqualTo(untouched.getStatus());
            assertThat(historyRepository.findByJobPostIdOrderByIdAsc(untouched.getId())).isEmpty();
        }
    }

    @Test
    void repeatedReconciliationRecordsEachTransitionOnce() {
        JobPost jobPost = saveJob(JobStatus.OPEN, deadline, workStart);
        fixAt(deadline);
        reconcileUntilIdle();
        reconcileUntilIdle();
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).hasSize(1);

        fixAt(workStart);
        reconcileUntilIdle();
        Long closedVersion = reload(jobPost).getVersion();
        reconcileUntilIdle();

        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(reload(jobPost).getVersion()).isEqualTo(closedVersion);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).hasSize(2);
        assertThat(singleCommand(jobPost).getJobVersion()).isEqualTo(closedVersion);
    }

    @Test
    void concurrentTransitionsOfSameJobRecordOnce() throws Exception {
        JobPost jobPost = saveJob(JobStatus.MATCHING, deadline, workStart);
        fixAt(workStart);

        List<Boolean> results = runConcurrently(4, () -> transitionService.applyIfDue(jobPost.getId()));

        assertThat(results).containsOnlyOnce(true);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).hasSize(1);
        assertThat(singleCommand(jobPost).getJobVersion()).isEqualTo(reload(jobPost).getVersion());
    }

    @Test
    void concurrentReconcilersRecordEachTransitionOnce() throws Exception {
        JobPost open = saveJob(JobStatus.OPEN, deadline, workStart);
        JobPost matching = saveJob(JobStatus.MATCHING, deadline.minusDays(1), workStart.minusDays(1));
        fixAt(deadline);

        runConcurrently(2, () -> {
            reconcileUntilIdle();
            return true;
        });

        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(open.getId())).hasSize(1);
        assertThat(commandRepository.findByJobPostId(open.getId())).isEmpty();
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(matching.getId())).hasSize(1);
        assertThat(commandRepository.findByJobPostId(matching.getId())).hasSize(1);
    }

    @Test
    void commandSaveFailureRollsBackWorkStartClose() {
        JobPost jobPost = saveJob(JobStatus.OPEN, deadline, workStart);
        Long versionBefore = reload(jobPost).getVersion();
        // 마감 전이가 만들 버전에 이미 명령이 있으면 유일 제약 위반으로 명령 저장이 실패한다.
        commandRepository.save(RecruitmentCompletionCommand.builder()
                .commandId(UUID.randomUUID().toString())
                .jobPostId(jobPost.getId())
                .jobVersion(versionBefore + 1)
                .nextAttemptAt(workStart)
                .build());
        fixAt(workStart);

        assertThatThrownBy(() -> transitionService.applyIfDue(jobPost.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertRolledBack(jobPost, JobStatus.OPEN, versionBefore);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).hasSize(1);
    }

    @Test
    void historySaveFailureRollsBackEveryTransition() throws Exception {
        JobPost unpublished = saveJob(JobStatus.PAYMENT_PENDING, deadline, workStart);
        JobPost open = saveJob(JobStatus.OPEN, deadline, workStart);
        JobPost matching = saveJob(JobStatus.MATCHING, deadline, workStart);
        List<JobPost> jobPosts = List.of(unpublished, open, matching);
        List<Long> versions = jobPosts.stream().map(jobPost -> reload(jobPost).getVersion()).toList();

        withHistoryInsertBlocked(jobPosts, () -> {
            fixAt(deadline);
            assertThatThrownBy(() -> transitionService.applyIfDue(unpublished.getId()))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> transitionService.applyIfDue(open.getId())).isInstanceOf(RuntimeException.class);
            fixAt(workStart);
            assertThatThrownBy(() -> transitionService.applyIfDue(matching.getId()))
                    .isInstanceOf(RuntimeException.class);
        });

        for (int index = 0; index < jobPosts.size(); index++) {
            assertRolledBack(jobPosts.get(index), jobPosts.get(index).getStatus(), versions.get(index));
            assertThat(commandRepository.findByJobPostId(jobPosts.get(index).getId())).isEmpty();
        }
    }

    @Test
    void failingJobDoesNotStopOtherTransitions() throws Exception {
        JobPost failing = saveJob(JobStatus.OPEN, deadline, workStart);
        JobPost other = saveJob(JobStatus.OPEN, deadline, workStart);
        Long failingVersion = reload(failing).getVersion();
        fixAt(deadline);

        withHistoryInsertBlocked(List.of(failing), this::reconcileUntilIdle);

        assertRolledBack(failing, JobStatus.OPEN, failingVersion);
        assertThat(reload(other).getStatus()).isEqualTo(JobStatus.MATCHING);
    }

    private void reconcileUntilIdle() {
        int idleRuns = 0;
        for (int run = 0; run < 1_000 && idleRuns < 2; run++) {
            idleRuns = reconciler.reconcile() == 0 ? idleRuns + 1 : 0;
        }
    }

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            futures.add(executor.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    // 지정한 공고의 이력 INSERT만 DB에서 실패시킨다. 다른 공고에는 영향을 주지 않는다.
    private void withHistoryInsertBlocked(List<JobPost> jobPosts, Runnable action) throws Exception {
        String trigger = "fail_schedule_history_" + jobPosts.get(0).getId();
        String ids = String.join(",", jobPosts.stream().map(jobPost -> jobPost.getId().toString()).toList());
        executeAsRoot("""
                CREATE TRIGGER %s BEFORE INSERT ON job_status_histories FOR EACH ROW
                BEGIN
                    IF NEW.job_post_id IN (%s) THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'history insert blocked by test';
                    END IF;
                END
                """.formatted(trigger, ids));
        try {
            action.run();
        } finally {
            executeAsRoot("DROP TRIGGER " + trigger);
        }
    }

    private void executeAsRoot(String sql) throws Exception {
        try (Connection connection = openLockObserverConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertRolledBack(JobPost jobPost, JobStatus status, Long version) {
        JobPost reloaded = reload(jobPost);
        assertThat(reloaded.getStatus()).isEqualTo(status);
        assertThat(reloaded.getVersion()).isEqualTo(version);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).isEmpty();
    }

    private RecruitmentCompletionCommand singleCommand(JobPost jobPost) {
        List<RecruitmentCompletionCommand> commands = commandRepository.findByJobPostId(jobPost.getId());
        assertThat(commands).hasSize(1);
        return commands.get(0);
    }

    private void fixAt(LocalDateTime time) {
        clock.fixAt(time.atZone(clock.getZone()).toInstant());
    }

    private JobPost reload(JobPost jobPost) {
        return jobPostRepository.findById(jobPost.getId()).orElseThrow();
    }

    private JobPost saveJob(JobStatus status, LocalDateTime applicationDeadline, LocalDateTime start) {
        return jobPostRepository.save(JobPostFixture.withStatus(JobPostFixture.jobPost()
                .workDate(start.toLocalDate())
                .startTime(start.toLocalTime())
                .endTime(LocalTime.of(18, 0))
                .applicationDeadline(applicationDeadline)
                .build(), status));
    }
}
