package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionCommandStatus;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionFailureType;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import com.workernotfound.job.support.StubMatchingServer;
import com.workernotfound.job.support.StubMatchingServer.StubResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 재시도 설정(기본 지연 2초, 최대 5분)에서 본문 수신 시간 초과가 일시 오류로 재시도되는지 검증한다.
 * 호출 제한시간만 테스트 시간을 줄이기 위해 짧게 둔다.
 */
class RecruitmentCompletionBodyFailureDispatchTests extends IntegrationTestSupport {

    private static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofSeconds(2);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(1);
    private static final StubMatchingServer SERVER = StubMatchingServer.start();

    @Autowired
    private RecruitmentCompletionDispatcher dispatcher;

    @Autowired
    private MatchingSeatReservationCommandService seatService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobMatchingSeatReservationRepository reservationRepository;

    @Autowired
    private RecruitmentCompletionCommandRepository commandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @DynamicPropertySource
    static void registerMatchingServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("job.matching-service.base-url", SERVER::baseUrl);
        registry.add("job.matching-service.connect-timeout", () -> "500ms");
        registry.add("job.matching-service.read-timeout", () -> CALL_TIMEOUT.toMillis() + "ms");
        registry.add("job.matching-service.call-timeout", () -> CALL_TIMEOUT.toMillis() + "ms");
    }

    @BeforeEach
    void setUp() {
        SERVER.reset();
        clock.fixAtNow();
        jdbcTemplate.update("update job_recruitment_completion_commands set status = 'SUCCEEDED' where status = 'PENDING'");
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    @AfterAll
    void stopServer() {
        SERVER.close();
    }

    @Test
    void retriesBodyReadTimeoutWithDefaultBackoffAndConvergesWithSameCommand() {
        RecruitmentCompletionCommand command = closeJob();
        SERVER.enqueue(StubResponse.partialSuccessBodyThenStall());

        assertThat(dispatcher.dispatch(command.getId())).isTrue();

        RecruitmentCompletionCommand failed = reload(command);
        assertThat(failed.getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.PENDING);
        assertThat(failed.getLastFailureType()).isEqualTo(RecruitmentCompletionFailureType.TIMEOUT);
        assertThat(failed.getLastFailureHttpStatus()).isEqualTo(200);
        assertThat(failed.getLastFailureCode()).isNull();
        // 계약 오류였다면 최대 지연(기본 5분)으로 예약됐을 것이다.
        assertThat(Duration.between(failed.getLastAttemptedAt(), failed.getNextAttemptAt()))
                .isEqualTo(DEFAULT_RETRY_BASE_DELAY);
        assertJobClosedAndSeatConsumed(command);

        clock.advance(DEFAULT_RETRY_BASE_DELAY.minusMillis(1));
        assertThat(dispatcher.dispatch(command.getId())).isFalse();
        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(SERVER.requests()).hasSize(1);

        clock.advance(Duration.ofMillis(1));
        assertThat(dispatcher.dispatchDue()).isOne();

        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
        assertThat(SERVER.requests()).hasSize(2).allSatisfy(request -> {
            assertThat(request.header("Idempotency-Key")).isEqualTo(command.getCommandId());
            assertThat(request.header("X-Job-Version")).isEqualTo(String.valueOf(command.getJobVersion()));
            assertThat(request.path()).isEqualTo(
                    "/api/applications/internal/jobs/" + command.getJobPostId() + "/recruitment-completion");
        });
        assertJobClosedAndSeatConsumed(command);
    }

    private void assertJobClosedAndSeatConsumed(RecruitmentCompletionCommand command) {
        assertThat(jobPostRepository.findById(command.getJobPostId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.CLOSED);
        assertThat(reservationRepository.findAll().stream()
                .filter(reservation -> reservation.getJobPostId().equals(command.getJobPostId())))
                .singleElement()
                .satisfies(reservation ->
                        assertThat(reservation.getStatus()).isEqualTo(MatchingSeatReservationStatus.CONSUMED));
    }

    private RecruitmentCompletionCommand closeJob() {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost().recruitCount(1).build());
        JobMatchingSeatReservation reservation = seatService.reserve(
                jobPost.getId(), 950_001L, 950_002L, 100L, "seat-" + UUID.randomUUID());
        seatService.confirm(jobPost.getId(), reservation.getId(), "confirm-" + UUID.randomUUID());
        return commandRepository.findByJobPostId(jobPost.getId()).get(0);
    }

    private RecruitmentCompletionCommand reload(RecruitmentCompletionCommand command) {
        return commandRepository.findById(command.getId()).orElseThrow();
    }
}
