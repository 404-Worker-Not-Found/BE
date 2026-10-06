package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobCloseRequestRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.JobStatusHistoryRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수동 마감의 상태 이력·모집 종료 알림 명령·요청 기록 중 하나라도 저장에 실패하면 공고 상태도 함께 롤백되는지 검증한다.
 */
class JobCloseRollbackTests extends IntegrationTestSupport {

    private static final AtomicLong SEQUENCE = new AtomicLong(1_500_000);

    @Autowired
    private JobCloseCommandService closeService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobStatusHistoryRepository historyRepository;

    @Autowired
    private RecruitmentCompletionCommandRepository commandRepository;

    @Autowired
    private JobCloseRequestRepository closeRequestRepository;

    @Test
    void historySaveFailureRollsBackClose() throws Exception {
        JobPost jobPost = saveOpenJob();
        Long versionBefore = reload(jobPost).getVersion();
        String key = newKey();

        withInsertBlocked("job_status_histories", "job_post_id", jobPost.getId(),
                () -> assertThatThrownBy(() -> close(jobPost, key)).isInstanceOf(RuntimeException.class));

        assertRolledBack(jobPost, versionBefore, key);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).isEmpty();
    }

    @Test
    void commandSaveFailureRollsBackClose() {
        JobPost jobPost = saveOpenJob();
        Long versionBefore = reload(jobPost).getVersion();
        String key = newKey();
        // 마감 전이가 만들 버전에 이미 명령이 있으면 유일 제약 위반으로 명령 저장이 실패한다.
        commandRepository.save(RecruitmentCompletionCommand.builder()
                .commandId(UUID.randomUUID().toString())
                .jobPostId(jobPost.getId())
                .jobVersion(versionBefore + 1)
                .nextAttemptAt(LocalDateTime.now())
                .build());

        assertThatThrownBy(() -> close(jobPost, key)).isInstanceOf(DataIntegrityViolationException.class);

        assertRolledBack(jobPost, versionBefore, key);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).hasSize(1);
    }

    @Test
    void requestSaveFailureRollsBackClose() throws Exception {
        JobPost jobPost = saveOpenJob();
        Long versionBefore = reload(jobPost).getVersion();
        String key = newKey();

        withInsertBlocked("job_close_requests", "job_post_id", jobPost.getId(),
                () -> assertThatThrownBy(() -> close(jobPost, key)).isInstanceOf(RuntimeException.class));

        assertRolledBack(jobPost, versionBefore, key);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).isEmpty();
    }

    private void assertRolledBack(JobPost jobPost, Long versionBefore, String key) {
        JobPost reloaded = reload(jobPost);
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(reloaded.getVersion()).isEqualTo(versionBefore);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).isEmpty();
        assertThat(closeRequestRepository.findByIdempotencyKey(key)).isEmpty();
    }

    // 지정한 공고의 INSERT만 DB에서 실패시킨다. 다른 테스트의 공고에는 영향을 주지 않는다.
    private void withInsertBlocked(String table, String column, Long jobPostId, Runnable action) throws Exception {
        String trigger = "fail_close_" + table + "_" + jobPostId;
        executeAsRoot("""
                CREATE TRIGGER %s BEFORE INSERT ON %s FOR EACH ROW
                BEGIN
                    IF NEW.%s = %d THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'insert blocked by test';
                    END IF;
                END
                """.formatted(trigger, table, column, jobPostId));
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

    private void close(JobPost jobPost, String key) {
        closeService.close(jobPost.getId(), jobPost.getOwnerId(), key);
    }

    private JobPost reload(JobPost jobPost) {
        return jobPostRepository.findById(jobPost.getId()).orElseThrow();
    }

    private JobPost saveOpenJob() {
        return jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().ownerId(nextId()).build()));
    }

    private static long nextId() {
        return SEQUENCE.incrementAndGet();
    }

    private static String newKey() {
        return "close-rollback-" + UUID.randomUUID();
    }
}
