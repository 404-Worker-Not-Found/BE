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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.workernotfound.job.support.ScriptedRecruitmentCompletionNotifier.FAKE_SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커밋 후 즉시 전송 경계: 내부 오류의 값이 로그로 나가지 않아야 하고, 스케줄러가 실행권 만료 뒤 같은 명령을 복구해야 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(ScriptedRecruitmentCompletionNotifier.Config.class)
class RecruitmentCompletionDispatchTriggerLoggingTests extends IntegrationTestSupport {

    private static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);
    private static final String TRIGGER_FAILURE_LOG = "모집 완료 알림 즉시 전송 실패(스케줄러가 복구): id=";

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
    private MutableClock clock;

    @DynamicPropertySource
    static void enableAfterCommitDispatch(DynamicPropertyRegistry registry) {
        registry.add("job.recruitment-completion.dispatch-after-commit", () -> "true");
    }

    @BeforeEach
    void setUp() {
        notifier.reset();
        clock.fixAtNow();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @Test
    void logsOnlySafeDiagnosticsAndSchedulerRecoversSameCommand(CapturedOutput output) throws Exception {
        notifier.failNextWith(ScriptedRecruitmentCompletionNotifier::exceptionCarryingSecret);

        RecruitmentCompletionCommand command = closeJob();
        awaitLog(output, TRIGGER_FAILURE_LOG + command.getId());

        assertThat(output.getAll())
                .contains("java.lang.IllegalArgumentException")
                .doesNotContain(FAKE_SECRET)
                .doesNotContain("X-Injected")
                .doesNotContain("invalid header value");
        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.PENDING);

        clock.advance(DEFAULT_LEASE);
        assertThat(dispatcher.dispatchDue()).isOne();

        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
        assertThat(notifier.calledCommandIds()).containsExactly(command.getCommandId(), command.getCommandId());
    }

    private void awaitLog(CapturedOutput output, String expected) throws InterruptedException {
        for (int i = 0; i < 100 && !output.getAll().contains(expected); i++) {
            Thread.sleep(50);
        }
        assertThat(output.getAll()).contains(expected);
    }

    private RecruitmentCompletionCommand closeJob() {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().recruitCount(1).build()));
        JobMatchingSeatReservation reservation = seatService.reserve(
                jobPost.getId(), 970_001L, 970_002L, 100L, "seat-" + UUID.randomUUID());
        seatService.confirm(jobPost.getId(), reservation.getId(), "confirm-" + UUID.randomUUID());
        return commandRepository.findByJobPostId(jobPost.getId()).get(0);
    }

    private RecruitmentCompletionCommand reload(RecruitmentCompletionCommand command) {
        return commandRepository.findById(command.getId()).orElseThrow();
    }
}
