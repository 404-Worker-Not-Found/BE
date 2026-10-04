package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.JobStatusHistoryRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RecruitmentCompletionReconcileTests extends IntegrationTestSupport {

    private static final AtomicLong SEQUENCE = new AtomicLong(900_000);

    @Autowired
    private RecruitmentCompletionReconciler reconciler;

    @Autowired
    private MatchingSeatReservationCommandService seatService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobStatusHistoryRepository historyRepository;

    @Autowired
    private RecruitmentCompletionCommandRepository commandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void closesOnlyJobsWhoseSeatsWereAllConsumedByEarlierImplementation() {
        JobPost legacyOpen = saveJob(2, JobStatus.OPEN);
        consumeAsEarlierImplementation(reserve(legacyOpen));
        consumeAsEarlierImplementation(reserve(legacyOpen));
        JobPost legacyMatching = saveJob(1, JobStatus.MATCHING);
        consumeAsEarlierImplementation(reserve(legacyMatching));
        JobPost partiallyConsumed = saveJob(2, JobStatus.OPEN);
        consumeAsEarlierImplementation(reserve(partiallyConsumed));
        reserve(partiallyConsumed);
        JobPost onlyReserved = saveJob(1, JobStatus.OPEN);
        reserve(onlyReserved);
        JobPost empty = saveJob(1, JobStatus.OPEN);
        Long untouchedVersion = reload(partiallyConsumed).getVersion();

        reconciler.reconcile();

        assertClosedOnce(legacyOpen, JobStatus.OPEN);
        assertClosedOnce(legacyMatching, JobStatus.MATCHING);
        for (JobPost unrelated : List.of(partiallyConsumed, onlyReserved, empty)) {
            assertThat(reload(unrelated).getStatus()).isEqualTo(JobStatus.OPEN);
            assertThat(historyRepository.findByJobPostIdOrderByIdAsc(unrelated.getId())).isEmpty();
            assertThat(commandRepository.findByJobPostId(unrelated.getId())).isEmpty();
        }
        assertThat(reload(partiallyConsumed).getVersion()).isEqualTo(untouchedVersion);
    }

    @Test
    void repeatedReconciliationDoesNotDuplicateCompletion() {
        JobPost legacy = saveJob(1, JobStatus.OPEN);
        consumeAsEarlierImplementation(reserve(legacy));

        reconciler.reconcile();
        Long closedVersion = reload(legacy).getVersion();
        reconciler.reconcile();

        assertClosedOnce(legacy, JobStatus.OPEN);
        assertThat(reload(legacy).getVersion()).isEqualTo(closedVersion);
    }

    @Test
    void leavesJobClosedByNormalConfirmationUntouched() {
        JobPost jobPost = saveJob(1, JobStatus.OPEN);
        JobMatchingSeatReservation reservation = reserve(jobPost);
        seatService.confirm(jobPost.getId(), reservation.getId(), "confirm-" + UUID.randomUUID());
        Long closedVersion = reload(jobPost).getVersion();

        reconciler.reconcile();

        assertClosedOnce(jobPost, JobStatus.OPEN);
        assertThat(reload(jobPost).getVersion()).isEqualTo(closedVersion);
    }

    private void assertClosedOnce(JobPost jobPost, JobStatus fromStatus) {
        JobPost closed = reload(jobPost);
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId()))
                .singleElement()
                .satisfies(history -> assertThat(history.getFromStatus()).isEqualTo(fromStatus));
        List<RecruitmentCompletionCommand> commands = commandRepository.findByJobPostId(jobPost.getId());
        assertThat(commands).singleElement()
                .satisfies(command -> assertThat(command.getJobVersion()).isEqualTo(closed.getVersion()));
    }

    // 모집 완료 구현 이전처럼 공고 상태를 바꾸지 않고 예약만 확정한다.
    private void consumeAsEarlierImplementation(JobMatchingSeatReservation reservation) {
        jdbcTemplate.update("""
                update job_matching_seat_reservations
                set status = 'CONSUMED', confirm_idempotency_key = ?, consumed_at = now(6), updated_at = now(6)
                where id = ?
                """, "legacy-confirm-" + UUID.randomUUID(), reservation.getId());
    }

    private JobMatchingSeatReservation reserve(JobPost jobPost) {
        return seatService.reserve(jobPost.getId(), nextId(), nextId(), 100L, "seat-" + UUID.randomUUID());
    }

    private JobPost saveJob(int recruitCount, JobStatus status) {
        return jobPostRepository.save(
                JobPostFixture.withStatus(JobPostFixture.jobPost().recruitCount(recruitCount).build(), status));
    }

    private JobPost reload(JobPost jobPost) {
        return jobPostRepository.findById(jobPost.getId()).orElseThrow();
    }

    private Long nextId() {
        return SEQUENCE.incrementAndGet();
    }
}
