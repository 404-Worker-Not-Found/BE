package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionCommandStatus;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import com.workernotfound.job.support.ScriptedRecruitmentCompletionNotifier;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.workernotfound.job.support.ScriptedRecruitmentCompletionNotifier.FAKE_SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄러 전송 경계: 예상하지 못한 내부 오류의 메시지·cause·suppressed에 담긴 값이 로그로 나가지 않아야 하고,
 * 그 오류가 이후 명령 처리를 막지 않으며 실행권 만료 뒤 같은 명령이 다시 전송되어야 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(ScriptedRecruitmentCompletionNotifier.Config.class)
class RecruitmentCompletionDispatcherLoggingTests extends IntegrationTestSupport {

    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);
    private static final AtomicLong SEQUENCE = new AtomicLong(960_000);

    @Autowired
    private RecruitmentCompletionDispatcher dispatcher;

    @Autowired
    private ScriptedRecruitmentCompletionNotifier notifier;

    @Autowired
    private MatchingSeatReservationCommandService seatService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private RecruitmentCompletionCommandRepository commandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        notifier.reset();
        clock.fixAtNow();
        jdbcTemplate.update("update job_recruitment_completion_commands set status = 'SUCCEEDED' where status = 'PENDING'");
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @Test
    void logsOnlySafeDiagnosticsAndContinuesWithNextCommandAfterInternalError(CapturedOutput output) {
        RecruitmentCompletionCommand failing = closeJob();
        RecruitmentCompletionCommand next = closeJob();
        notifier.failNextWith(ScriptedRecruitmentCompletionNotifier::exceptionCarryingSecret);

        // 실패한 명령은 세지 않고, 같은 배치의 다음 명령은 계속 처리한다.
        assertThat(dispatcher.dispatchDue()).isOne();

        assertThat(notifier.calledCommandIds()).containsExactly(failing.getCommandId(), next.getCommandId());
        assertThat(reload(failing).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.PENDING);
        assertThat(reload(next).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
        assertThat(output.getAll())
                .contains("모집 완료 알림 전송 처리 실패: id=" + failing.getId())
                .contains("java.lang.IllegalArgumentException")
                .doesNotContain(FAKE_SECRET)
                .doesNotContain("X-Injected")
                .doesNotContain("invalid header value");
    }

    @Test
    void retriesSameCommandAfterLeaseExpiresFollowingInternalError(CapturedOutput output) {
        RecruitmentCompletionCommand command = closeJob();
        notifier.failNextWith(ScriptedRecruitmentCompletionNotifier::exceptionCarryingSecret);
        assertThat(dispatcher.dispatchDue()).isZero();

        // 실행권이 남아 있는 동안에는 다시 보내지 않는다.
        clock.advance(DEFAULT_LEASE.minusSeconds(1));
        assertThat(dispatcher.dispatchDue()).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(dispatcher.dispatchDue()).isOne();

        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
        assertThat(notifier.calledCommandIds()).containsExactly(command.getCommandId(), command.getCommandId());
        assertThat(output.getAll()).doesNotContain(FAKE_SECRET);
    }

    private RecruitmentCompletionCommand closeJob() {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost().recruitCount(1).build());
        JobMatchingSeatReservation reservation = seatService.reserve(
                jobPost.getId(), SEQUENCE.incrementAndGet(), SEQUENCE.incrementAndGet(), 100L, "seat-" + UUID.randomUUID());
        seatService.confirm(jobPost.getId(), reservation.getId(), "confirm-" + UUID.randomUUID());
        return commandRepository.findByJobPostId(jobPost.getId()).get(0);
    }

    private RecruitmentCompletionCommand reload(RecruitmentCompletionCommand command) {
        return commandRepository.findById(command.getId()).orElseThrow();
    }
}
