package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.JobStatusHistory;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.JobStatusHistoryRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 지원 마감·근무 시작 경과에 따른 자동 상태 전이를 공고별 판단({@link JobScheduleTransitionService#applyIfDue})으로 검증한다.
 * 경계 시각은 {@link MutableClock}으로 고정한다.
 */
class JobScheduleTransitionTests extends IntegrationTestSupport {

    private static final AtomicLong SEQUENCE = new AtomicLong(1_100_000);
    private static final Duration NANO = Duration.ofNanos(1);

    // 근무 시작 09:00, 지원 마감 07:00. 실제 현재 시각과 겹치지 않도록 며칠 뒤로 둔다.
    private final LocalDateTime workStart = LocalDate.now().plusDays(5).atTime(9, 0);
    private final LocalDateTime deadline = workStart.minusHours(2);

    @Autowired
    private JobScheduleTransitionService transitionService;

    @Autowired
    private MatchingSeatReservationCommandService seatService;

    @Autowired
    private JobApplicationAdmissionCommandService admissionService;

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

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @Test
    void paymentPendingJobClosesExactlyAtApplicationDeadlineWithoutNotification() {
        JobPost jobPost = saveJob(JobStatus.PAYMENT_PENDING);
        Long versionBefore = reload(jobPost).getVersion();

        fixAt(deadline.minus(NANO));
        assertThat(transitionService.applyIfDue(jobPost.getId())).isFalse();
        assertUnchanged(jobPost, JobStatus.PAYMENT_PENDING, versionBefore);

        fixAt(deadline);
        assertThat(transitionService.applyIfDue(jobPost.getId())).isTrue();

        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.CLOSED);
        assertSingleHistory(jobPost, JobStatus.PAYMENT_PENDING, JobStatus.CLOSED,
                JobScheduleTransitionService.APPLICATION_DEADLINE_PASSED_REASON);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).isEmpty();
    }

    @Test
    void openJobMovesToMatchingExactlyAtApplicationDeadlineWithoutNotification() {
        JobPost jobPost = saveJob(JobStatus.OPEN);
        Long versionBefore = reload(jobPost).getVersion();

        fixAt(deadline.minus(NANO));
        assertThat(transitionService.applyIfDue(jobPost.getId())).isFalse();
        assertUnchanged(jobPost, JobStatus.OPEN, versionBefore);

        fixAt(deadline);
        assertThat(transitionService.applyIfDue(jobPost.getId())).isTrue();

        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.MATCHING);
        assertThat(reload(jobPost).getVersion()).isEqualTo(versionBefore + 1);
        assertSingleHistory(jobPost, JobStatus.OPEN, JobStatus.MATCHING,
                JobScheduleTransitionService.APPLICATION_DEADLINE_PASSED_REASON);
        // 알림을 보내면 matching-service가 기존 지원자를 종료시킨다.
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).isEmpty();
    }

    @Test
    void matchingJobClosesExactlyAtWorkStartWithNotificationOfClosedVersion() {
        JobPost jobPost = saveJob(JobStatus.MATCHING);
        Long versionBefore = reload(jobPost).getVersion();

        fixAt(workStart.minus(NANO));
        assertThat(transitionService.applyIfDue(jobPost.getId())).isFalse();
        assertUnchanged(jobPost, JobStatus.MATCHING, versionBefore);

        fixAt(workStart);
        assertThat(transitionService.applyIfDue(jobPost.getId())).isTrue();

        JobPost closed = reload(jobPost);
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertSingleHistory(jobPost, JobStatus.MATCHING, JobStatus.CLOSED,
                JobScheduleTransitionService.WORK_STARTED_REASON);
        assertThat(singleCommand(jobPost).getJobVersion()).isEqualTo(closed.getVersion());
    }

    @Test
    void openJobPastBothBoundariesClosesOnceForWorkStart() {
        JobPost jobPost = saveJob(JobStatus.OPEN);

        fixAt(workStart);
        assertThat(transitionService.applyIfDue(jobPost.getId())).isTrue();
        assertThat(transitionService.applyIfDue(jobPost.getId())).isFalse();

        JobPost closed = reload(jobPost);
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertSingleHistory(jobPost, JobStatus.OPEN, JobStatus.CLOSED, JobScheduleTransitionService.WORK_STARTED_REASON);
        assertThat(singleCommand(jobPost).getJobVersion()).isEqualTo(closed.getVersion());
    }

    @Test
    void closedJobIsNotTransitionedAgain() {
        JobPost jobPost = saveJob(JobStatus.CLOSED);
        Long versionBefore = reload(jobPost).getVersion();

        fixAt(workStart.plusHours(1));

        assertThat(transitionService.applyIfDue(jobPost.getId())).isFalse();
        assertUnchanged(jobPost, JobStatus.CLOSED, versionBefore);
    }

    @Test
    void existingApplicantsKeepMatchingAndCompleteRecruitmentAfterDeadline() {
        JobPost jobPost = saveJob(JobStatus.OPEN);
        fixAt(deadline);
        transitionService.applyIfDue(jobPost.getId());
        assertThatThrownBy(() -> admissionService.create(jobPost.getId(), nextId(), newKey()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.JOB_NOT_OPEN));

        fixAt(deadline.plusMinutes(30));
        JobMatchingSeatReservation reservation = reserve(jobPost);
        seatService.confirm(jobPost.getId(), reservation.getId(), newKey());

        JobPost closed = reload(jobPost);
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId()))
                .extracting(JobStatusHistory::getFromStatus, JobStatusHistory::getToStatus, JobStatusHistory::getReason)
                .containsExactly(
                        tuple(JobStatus.OPEN, JobStatus.MATCHING,
                                JobScheduleTransitionService.APPLICATION_DEADLINE_PASSED_REASON),
                        tuple(JobStatus.MATCHING, JobStatus.CLOSED,
                                JobRecruitmentCompletionService.RECRUITMENT_FILLED_REASON));
        assertThat(singleCommand(jobPost).getJobVersion()).isEqualTo(closed.getVersion());
    }

    @Test
    void reservedSeatCannotBeConfirmedAfterWorkStartCloseButCanBeReleased() {
        JobPost jobPost = saveJob(JobStatus.MATCHING, 2);
        fixAt(workStart.minusMinutes(5));
        JobMatchingSeatReservation consumed = reserve(jobPost);
        String confirmKey = newKey();
        seatService.confirm(jobPost.getId(), consumed.getId(), confirmKey);
        JobMatchingSeatReservation reserved = reserve(jobPost);

        fixAt(workStart);
        transitionService.applyIfDue(jobPost.getId());

        assertThatThrownBy(() -> seatService.confirm(jobPost.getId(), reserved.getId(), newKey()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(JobErrorCode.JOB_NOT_MATCHABLE));
        assertThat(reservationStatus(reserved)).isEqualTo(MatchingSeatReservationStatus.RESERVED);
        // 확정 응답이 유실된 원래 키의 재요청은 마감 검사보다 먼저 성공한다.
        assertThat(seatService.confirm(jobPost.getId(), consumed.getId(), confirmKey).getStatus())
                .isEqualTo(MatchingSeatReservationStatus.CONSUMED);
        // Saga 보상의 자리 반환은 마감 공고에서도 성공한다.
        assertThat(seatService.release(jobPost.getId(), reserved.getId(), newKey()).getStatus())
                .isEqualTo(MatchingSeatReservationStatus.RELEASED);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).hasSize(1);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).hasSize(1);
    }

    private void assertUnchanged(JobPost jobPost, JobStatus status, Long version) {
        JobPost reloaded = reload(jobPost);
        assertThat(reloaded.getStatus()).isEqualTo(status);
        assertThat(reloaded.getVersion()).isEqualTo(version);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).isEmpty();
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).isEmpty();
    }

    private void assertSingleHistory(JobPost jobPost, JobStatus from, JobStatus to, String reason) {
        List<JobStatusHistory> histories = historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId());
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getFromStatus()).isEqualTo(from);
        assertThat(histories.get(0).getToStatus()).isEqualTo(to);
        assertThat(histories.get(0).getReason()).isEqualTo(reason);
        assertThat(histories.get(0).getCreatedAt())
                .isEqualTo(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
    }

    private RecruitmentCompletionCommand singleCommand(JobPost jobPost) {
        List<RecruitmentCompletionCommand> commands = commandRepository.findByJobPostId(jobPost.getId());
        assertThat(commands).hasSize(1);
        return commands.get(0);
    }

    private MatchingSeatReservationStatus reservationStatus(JobMatchingSeatReservation reservation) {
        return reservationRepository.findById(reservation.getId()).orElseThrow().getStatus();
    }

    private JobMatchingSeatReservation reserve(JobPost jobPost) {
        return seatService.reserve(jobPost.getId(), nextId(), nextId(), nextId(), newKey());
    }

    private void fixAt(LocalDateTime time) {
        clock.fixAt(time.atZone(clock.getZone()).toInstant());
    }

    private JobPost reload(JobPost jobPost) {
        return jobPostRepository.findById(jobPost.getId()).orElseThrow();
    }

    private JobPost saveJob(JobStatus status) {
        return saveJob(status, 1);
    }

    private JobPost saveJob(JobStatus status, int recruitCount) {
        return jobPostRepository.save(JobPostFixture.withStatus(JobPostFixture.jobPost()
                .workDate(workStart.toLocalDate())
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .applicationDeadline(deadline)
                .recruitCount(recruitCount)
                .build(), status));
    }

    private static long nextId() {
        return SEQUENCE.incrementAndGet();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
