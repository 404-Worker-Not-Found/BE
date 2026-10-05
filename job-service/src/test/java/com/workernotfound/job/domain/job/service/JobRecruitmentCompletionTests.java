package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.JobStatusHistory;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionCommandStatus;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.JobStatusHistoryRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobRecruitmentCompletionTests extends IntegrationTestSupport {

    private static final AtomicLong SEQUENCE = new AtomicLong(600_000);

    @Autowired
    private MatchingSeatReservationCommandService seatService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobMatchingSeatReservationRepository reservationRepository;

    @Autowired
    private JobStatusHistoryRepository historyRepository;

    @Autowired
    private RecruitmentCompletionCommandRepository commandRepository;

    @Autowired
    private MutableClock clock;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(3);
        clock.fixAtNow();
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
        clock.reset();
    }

    @Test
    void doesNotCloseWhenReservationsOnlyFillSeats() {
        JobPost jobPost = saveJob(2);
        reserve(jobPost);
        reserve(jobPost);

        assertOpenWithoutCompletion(jobPost);
    }

    @Test
    void doesNotCloseWhileOnlySomeSeatsAreConsumed() {
        JobPost jobPost = saveJob(2);
        confirm(reserve(jobPost));
        reserve(jobPost);

        assertOpenWithoutCompletion(jobPost);
    }

    @Test
    void lastConfirmationClosesJobWithHistoryAndCommand() {
        JobPost jobPost = saveJob(2);
        JobMatchingSeatReservation first = reserve(jobPost);
        JobMatchingSeatReservation last = reserve(jobPost);
        confirm(first);

        confirm(last);

        JobPost closed = reload(jobPost);
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        List<JobStatusHistory> histories = historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId());
        assertThat(histories).singleElement().satisfies(history -> {
            assertThat(history.getFromStatus()).isEqualTo(JobStatus.OPEN);
            assertThat(history.getToStatus()).isEqualTo(JobStatus.CLOSED);
            assertThat(history.getReason()).isEqualTo("SYSTEM:RECRUITMENT_FILLED");
            assertThat(history.getCreatedAt()).isNotNull();
        });
        RecruitmentCompletionCommand command = singleCommand(jobPost);
        assertThat(command.getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.PENDING);
        assertThat(command.getAttemptCount()).isZero();
        assertThat(UUID.fromString(command.getCommandId()).toString()).isEqualTo(command.getCommandId());
        // 알림 버전은 예약 발급 당시 버전이 아니라 모집 완료 전이가 반영된 공고 버전이다.
        assertThat(command.getJobVersion()).isEqualTo(closed.getVersion());
        assertThat(command.getJobVersion()).isGreaterThan(last.getJobVersion());
        assertThat(command.getNextAttemptAt()).isEqualTo(histories.get(0).getCreatedAt());
    }

    @Test
    void closesMatchingJobWhenLastSeatIsConfirmed() {
        JobPost jobPost = jobPostRepository.save(
                JobPostFixture.withStatus(JobPostFixture.jobPost().recruitCount(1).build(), JobStatus.MATCHING));

        confirm(reserve(jobPost));

        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId()))
                .singleElement()
                .satisfies(history -> assertThat(history.getFromStatus()).isEqualTo(JobStatus.MATCHING));
    }

    @Test
    void repeatedConfirmationOnClosedJobSucceedsWithoutDuplicates() {
        JobPost jobPost = saveJob(1);
        JobMatchingSeatReservation reservation = reserve(jobPost);
        String key = newKey();
        seatService.confirm(jobPost.getId(), reservation.getId(), key);
        Long closedVersion = reload(jobPost).getVersion();

        JobMatchingSeatReservation retried = seatService.confirm(jobPost.getId(), reservation.getId(), key);

        assertThat(retried.getStatus()).isEqualTo(MatchingSeatReservationStatus.CONSUMED);
        assertThat(retried.getJobVersion()).isEqualTo(reservation.getJobVersion());
        assertThat(reload(jobPost).getVersion()).isEqualTo(closedVersion);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).hasSize(1);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).hasSize(1);
    }

    @RepeatedTest(3)
    void concurrentConfirmationsCreateOneCompletion() throws Exception {
        JobPost jobPost = saveJob(3);
        List<JobMatchingSeatReservation> reservations = List.of(reserve(jobPost), reserve(jobPost), reserve(jobPost));

        CountDownLatch start = new CountDownLatch(1);
        List<Future<JobMatchingSeatReservation>> futures = new ArrayList<>();
        for (JobMatchingSeatReservation reservation : reservations) {
            futures.add(executorService.submit(() -> {
                start.await();
                return seatService.confirm(jobPost.getId(), reservation.getId(), newKey());
            }));
        }
        start.countDown();
        for (Future<JobMatchingSeatReservation> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(MatchingSeatReservationStatus.CONSUMED);
        }

        JobPost closed = reload(jobPost);
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).hasSize(1);
        assertThat(singleCommand(jobPost).getJobVersion()).isEqualTo(closed.getVersion());
    }

    @Test
    void commandSaveFailureRollsBackLastConfirmation() {
        JobPost jobPost = saveJob(1);
        JobMatchingSeatReservation reservation = reserve(jobPost);
        Long versionBefore = reload(jobPost).getVersion();
        // 완료 전이가 만들 버전에 이미 명령이 있으면 유일 제약 위반으로 명령 저장이 실패한다.
        commandRepository.save(RecruitmentCompletionCommand.builder()
                .commandId(UUID.randomUUID().toString())
                .jobPostId(jobPost.getId())
                .jobVersion(versionBefore + 1)
                .nextAttemptAt(clock.instant().atZone(clock.getZone()).toLocalDateTime())
                .build());

        assertThatThrownBy(() -> confirm(reservation)).isInstanceOf(DataIntegrityViolationException.class);

        assertRolledBack(jobPost, reservation, versionBefore);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).hasSize(1);
    }

    @Test
    void historySaveFailureRollsBackLastConfirmation() throws Exception {
        JobPost jobPost = saveJob(1);
        JobMatchingSeatReservation reservation = reserve(jobPost);
        Long versionBefore = reload(jobPost).getVersion();
        String trigger = "fail_job_status_history_" + jobPost.getId();
        // 이 공고의 이력 INSERT만 DB에서 실패시킨다. 다른 테스트의 공고에는 영향을 주지 않는다.
        executeAsRoot("""
                CREATE TRIGGER %s BEFORE INSERT ON job_status_histories FOR EACH ROW
                BEGIN
                    IF NEW.job_post_id = %d THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'history insert blocked by test';
                    END IF;
                END
                """.formatted(trigger, jobPost.getId()));
        try {
            assertThatThrownBy(() -> confirm(reservation)).isInstanceOf(RuntimeException.class);
        } finally {
            executeAsRoot("DROP TRIGGER " + trigger);
        }

        assertRolledBack(jobPost, reservation, versionBefore);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).isEmpty();
    }

    private void assertRolledBack(JobPost jobPost, JobMatchingSeatReservation reservation, Long versionBefore) {
        JobPost reloaded = reload(jobPost);
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(reloaded.getVersion()).isEqualTo(versionBefore);
        JobMatchingSeatReservation reloadedReservation = reservationRepository.findById(reservation.getId()).orElseThrow();
        assertThat(reloadedReservation.getStatus()).isEqualTo(MatchingSeatReservationStatus.RESERVED);
        assertThat(reloadedReservation.getConfirmIdempotencyKey()).isNull();
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).isEmpty();
    }

    private void assertOpenWithoutCompletion(JobPost jobPost) {
        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).isEmpty();
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).isEmpty();
    }

    private RecruitmentCompletionCommand singleCommand(JobPost jobPost) {
        List<RecruitmentCompletionCommand> commands = commandRepository.findByJobPostId(jobPost.getId());
        assertThat(commands).hasSize(1);
        return commands.get(0);
    }

    private void executeAsRoot(String sql) throws Exception {
        try (Connection connection = openLockObserverConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private JobMatchingSeatReservation reserve(JobPost jobPost) {
        return seatService.reserve(jobPost.getId(), nextId(), nextId(), 100L, newKey());
    }

    private JobMatchingSeatReservation confirm(JobMatchingSeatReservation reservation) {
        return seatService.confirm(reservation.getJobPostId(), reservation.getId(), newKey());
    }

    private JobPost reload(JobPost jobPost) {
        return jobPostRepository.findById(jobPost.getId()).orElseThrow();
    }

    private JobPost saveJob(int recruitCount) {
        return jobPostRepository.save(JobPostFixture.jobPost().recruitCount(recruitCount).build());
    }

    private Long nextId() {
        return SEQUENCE.incrementAndGet();
    }

    private String newKey() {
        return "seat-command-" + UUID.randomUUID();
    }
}
