package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionCommandStatus;
import com.workernotfound.job.domain.job.entity.enums.RecruitmentCompletionFailureType;
import com.workernotfound.job.domain.job.exception.RecruitmentCompletionNotificationException;
import com.workernotfound.job.domain.job.port.RecruitmentCompletionNotifier;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import com.workernotfound.job.support.StubMatchingServer;
import com.workernotfound.job.support.StubMatchingServer.RecordedRequest;
import com.workernotfound.job.support.StubMatchingServer.StubResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전송·재시도·복구 검증. 명령 저장·실행권·결과 기록은 실제 MySQL로, HTTP는 실제 소켓의 matching-service 대역으로 확인한다.
 * matching-service의 수신 처리 자체는 matching-service 테스트에서 검증한다.
 */
class RecruitmentCompletionDispatchTests extends IntegrationTestSupport {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    private static final Duration LEASE = Duration.ofSeconds(3);
    private static final Duration BASE_DELAY = Duration.ofSeconds(2);
    private static final Duration MAX_DELAY = Duration.ofSeconds(5);
    private static final AtomicLong SEQUENCE = new AtomicLong(700_000);
    private static final StubMatchingServer SERVER = StubMatchingServer.start();

    @Autowired
    private RecruitmentCompletionDispatcher dispatcher;

    @Autowired
    private RecruitmentCompletionCommandTransactionService transactionService;

    @Autowired
    private RecruitmentCompletionNotifier notifier;

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
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MutableClock clock;

    @DynamicPropertySource
    static void registerMatchingServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("job.matching-service.base-url", SERVER::baseUrl);
        registry.add("job.matching-service.connect-timeout", () -> "500ms");
        registry.add("job.matching-service.read-timeout", () -> READ_TIMEOUT.toMillis() + "ms");
        registry.add("job.recruitment-completion.lease-duration", () -> LEASE.toSeconds() + "s");
        registry.add("job.recruitment-completion.retry-base-delay", () -> BASE_DELAY.toSeconds() + "s");
        registry.add("job.recruitment-completion.retry-max-delay", () -> MAX_DELAY.toSeconds() + "s");
    }

    @BeforeEach
    void setUp() {
        SERVER.reset();
        clock.fixAtNow();
        // 다른 테스트가 남긴 미완료 명령이 대역 서버 응답 순서를 소비하지 않도록 정리한다.
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
    void sendsStoredCommandWithContractHeadersAndRecordsSuccess() {
        RecruitmentCompletionCommand command = closeJob();

        assertThat(dispatcher.dispatch(command.getId())).isTrue();

        assertThat(SERVER.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo(
                    "/api/applications/internal/jobs/" + command.getJobPostId() + "/recruitment-completion");
            assertThat(request.header("X-Internal-Secret")).isEqualTo("test-internal-secret");
            assertThat(request.header("X-Job-Version")).isEqualTo(String.valueOf(command.getJobVersion()));
            assertThat(request.header("Idempotency-Key")).isEqualTo(command.getCommandId());
            assertThat(request.bodyLength()).isZero();
        });
        RecruitmentCompletionCommand sent = reload(command);
        assertThat(sent.getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
        assertThat(sent.getSucceededAt()).isNotNull();
        assertThat(sent.getAttemptCount()).isOne();
        assertThat(sent.getLeaseToken()).isNull();
        assertThat(sent.getLeaseExpiresAt()).isNull();
    }

    @Test
    void holdsNoTransactionOrJobLockWhileWaitingForHttpResponse() {
        RecruitmentCompletionCommand command = closeJob();
        AtomicReference<LockObservation> observation = new AtomicReference<>();
        SERVER.onRequest(request -> observation.set(observeLocks(command)));

        dispatcher.dispatch(command.getId());

        assertThat(observation.get()).isEqualTo(new LockObservation(0, true, true));
        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
    }

    @Test
    void lockObservationDetectsHeldJobLock() {
        RecruitmentCompletionCommand command = closeJob();

        // 대조군: 공고 행 잠금을 잡은 트랜잭션 안에서는 관찰 결과가 달라야 위 검증이 의미를 갖는다.
        LockObservation observation = transactionTemplate.execute(status -> {
            jobPostRepository.findByIdForUpdate(command.getJobPostId()).orElseThrow();
            return observeLocks(command);
        });

        assertThat(observation.openTransactions()).isPositive();
        assertThat(observation.isJobRowFree()).isFalse();
    }

    @Test
    void retriesConflictServerErrorAndTimeoutWithSameCommandIdAndVersion() {
        RecruitmentCompletionCommand command = closeJob();
        SERVER.enqueue(
                StubResponse.sagaInProgress(),
                StubResponse.error(503, "GLOBAL-503-001", "unavailable"),
                StubResponse.success().delayedBy(READ_TIMEOUT.multipliedBy(3))
        );

        dispatchAndAssertFailure(command, RecruitmentCompletionFailureType.CONFLICT, 409, "APPLICATION-409-006");
        advancePastNextAttempt(command);
        dispatchAndAssertFailure(command, RecruitmentCompletionFailureType.SERVER_ERROR, 503, "GLOBAL-503-001");
        advancePastNextAttempt(command);
        dispatchAndAssertFailure(command, RecruitmentCompletionFailureType.TIMEOUT, null, null);
        advancePastNextAttempt(command);
        assertThat(dispatcher.dispatch(command.getId())).isTrue();

        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
        assertThat(reload(command).getAttemptCount()).isEqualTo(4);
        assertAllRequestsCarry(command, 4);
    }

    @Test
    void doesNotCallAgainBeforeNextAttemptTime() {
        RecruitmentCompletionCommand command = closeJob();
        SERVER.enqueue(StubResponse.sagaInProgress());
        dispatcher.dispatch(command.getId());
        LocalDateTime nextAttemptAt = reload(command).getNextAttemptAt();

        clock.advance(BASE_DELAY.minusMillis(1));
        assertThat(dispatcher.dispatch(command.getId())).isFalse();
        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(SERVER.requests()).hasSize(1);

        clock.advance(Duration.ofMillis(1));
        assertThat(LocalDateTime.now(clock)).isAfterOrEqualTo(nextAttemptAt);
        assertThat(dispatcher.dispatchDue()).isOne();
        assertThat(SERVER.requests()).hasSize(2);
    }

    @Test
    void appliesCappedExponentialBackoffToTransientFailures() {
        RecruitmentCompletionCommand command = closeJob();
        SERVER.respondByDefault(StubResponse.sagaInProgress());

        List<Duration> delays = List.of(
                dispatchAndMeasureDelay(command),
                dispatchAndMeasureDelay(command),
                dispatchAndMeasureDelay(command),
                dispatchAndMeasureDelay(command)
        );

        assertThat(delays).containsExactly(BASE_DELAY, BASE_DELAY.multipliedBy(2), MAX_DELAY, MAX_DELAY);
        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.PENDING);
    }

    @Test
    void keepsAuthenticationFailurePendingAndRetriesSameCommandAfterFix() {
        RecruitmentCompletionCommand command = closeJob();
        SERVER.enqueue(StubResponse.error(403, "GLOBAL-403-001", "forbidden"));

        Duration delay = dispatchAndMeasureDelay(command);

        RecruitmentCompletionCommand failed = reload(command);
        assertThat(delay).isEqualTo(MAX_DELAY);
        assertThat(failed.getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.PENDING);
        assertThat(failed.getLastFailureType()).isEqualTo(RecruitmentCompletionFailureType.AUTHENTICATION);
        assertThat(failed.getLastFailureHttpStatus()).isEqualTo(403);

        // 원인 해결 후(대역 서버가 성공 응답) 같은 명령 ID로 다시 처리된다.
        clock.advance(MAX_DELAY);
        assertThat(dispatcher.dispatchDue()).isOne();
        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
        assertAllRequestsCarry(command, 2);
    }

    @Test
    void schedulerSendsCommittedCommandWhenNoImmediateDispatchRan() {
        // 커밋 후 즉시 전송은 이 테스트 설정에서 꺼져 있다. 마감 직후 프로세스가 멈춘 상황과 같다.
        RecruitmentCompletionCommand command = closeJob();
        assertThat(SERVER.requests()).isEmpty();

        assertThat(dispatcher.dispatchDue()).isOne();

        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
        assertAllRequestsCarry(command, 1);
    }

    @Test
    void takesOverExpiredLeaseAndIgnoresLateResultFromPreviousExecutor() {
        RecruitmentCompletionCommand command = closeJob();
        // 실행권만 얻고 멈춘 실행자
        String staleToken = claimAsCrashedExecutor(command);
        assertThat(dispatcher.dispatch(command.getId())).isFalse();
        assertThat(dispatcher.dispatchDue()).isZero();

        clock.advance(LEASE);
        AtomicReference<Boolean> lateResultApplied = new AtomicReference<>();
        SERVER.enqueue(StubResponse.sagaInProgress());
        // 새 실행자가 응답을 기다리는 동안 이전 실행자의 늦은 성공 결과가 도착한다.
        SERVER.onRequest(request -> lateResultApplied.set(
                transactionService.markSucceeded(command.getId(), staleToken, storedNow())));
        assertThat(dispatcher.dispatch(command.getId())).isTrue();

        assertThat(lateResultApplied.get()).isFalse();
        RecruitmentCompletionCommand afterTakeover = reload(command);
        assertThat(afterTakeover.getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.PENDING);
        assertThat(afterTakeover.getLastFailureType()).isEqualTo(RecruitmentCompletionFailureType.CONFLICT);
        assertThat(afterTakeover.getAttemptCount()).isEqualTo(2);

        SERVER.onRequest(request -> {
        });
        advancePastNextAttempt(command);
        assertThat(dispatcher.dispatch(command.getId())).isTrue();
        // 성공 이후 도착한 이전 실행자의 늦은 실패도 반영되지 않는다.
        assertThat(transactionService.markFailed(command.getId(), staleToken, new RecruitmentCompletionNotificationException(
                RecruitmentCompletionFailureType.NETWORK, null, null), storedNow(), storedNow())).isFalse();
        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
        assertAllRequestsCarry(command, 2);
    }

    @Test
    void concurrentExecutorsSendCommandOnce() throws Exception {
        RecruitmentCompletionCommand command = closeJob();
        SERVER.respondByDefault(StubResponse.success().delayedBy(Duration.ofMillis(200)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return dispatcher.dispatch(command.getId());
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return dispatcher.dispatch(command.getId());
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }

        assertAllRequestsCarry(command, 1);
        assertThat(reload(command).getAttemptCount()).isOne();
    }

    @Test
    void convergesWhenRemoteSucceededButResponseWasLost() {
        RecruitmentCompletionCommand command = closeJob();
        // 상대는 처리했지만 응답이 읽기 타임아웃 뒤에 도착한다.
        SERVER.enqueue(StubResponse.success().delayedBy(READ_TIMEOUT.multipliedBy(3)));

        dispatchAndAssertFailure(command, RecruitmentCompletionFailureType.TIMEOUT, null, null);
        advancePastNextAttempt(command);
        assertThat(dispatcher.dispatch(command.getId())).isTrue();

        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
        assertAllRequestsCarry(command, 2);
    }

    @Test
    void convergesWhenProcessStoppedAfterHttpSuccessBeforeLocalRecord() {
        RecruitmentCompletionCommand command = closeJob();
        claimAsCrashedExecutor(command);
        // 실행권을 가진 실행자가 HTTP 성공까지 받은 뒤 결과를 기록하지 못하고 멈췄다.
        notifier.notifyRecruitmentCompleted(command.getJobPostId(), command.getJobVersion(), command.getCommandId());
        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.PENDING);

        clock.advance(LEASE);
        assertThat(dispatcher.dispatchDue()).isOne();

        assertThat(reload(command).getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.SUCCEEDED);
        assertAllRequestsCarry(command, 2);
    }

    @Test
    void notificationFailureKeepsConfirmedSeatAndClosedJob() {
        RecruitmentCompletionCommand command = closeJob();
        SERVER.respondByDefault(StubResponse.error(500, "GLOBAL-500-001", "error"));

        dispatcher.dispatch(command.getId());
        advancePastNextAttempt(command);
        dispatcher.dispatch(command.getId());

        assertThat(jobPostRepository.findById(command.getJobPostId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.CLOSED);
        assertThat(reservationRepository.findAll().stream()
                .filter(reservation -> reservation.getJobPostId().equals(command.getJobPostId())))
                .singleElement()
                .satisfies(reservation ->
                        assertThat(reservation.getStatus()).isEqualTo(MatchingSeatReservationStatus.CONSUMED));
        RecruitmentCompletionCommand failed = reload(command);
        assertThat(failed.getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.PENDING);
        assertThat(failed.getJobVersion()).isEqualTo(command.getJobVersion());
        assertThat(failed.getCommandId()).isEqualTo(command.getCommandId());
    }

    private void dispatchAndAssertFailure(
            RecruitmentCompletionCommand command,
            RecruitmentCompletionFailureType type,
            Integer httpStatus,
            String code
    ) {
        assertThat(dispatcher.dispatch(command.getId())).isTrue();
        RecruitmentCompletionCommand failed = reload(command);
        assertThat(failed.getStatus()).isEqualTo(RecruitmentCompletionCommandStatus.PENDING);
        assertThat(failed.getLastFailureType()).isEqualTo(type);
        assertThat(failed.getLastFailureHttpStatus()).isEqualTo(httpStatus);
        assertThat(failed.getLastFailureCode()).isEqualTo(code);
        assertThat(failed.getLeaseToken()).isNull();
    }

    private Duration dispatchAndMeasureDelay(RecruitmentCompletionCommand command) {
        advancePastNextAttempt(command);
        assertThat(dispatcher.dispatch(command.getId())).isTrue();
        RecruitmentCompletionCommand failed = reload(command);
        return Duration.between(failed.getLastAttemptedAt(), failed.getNextAttemptAt());
    }

    private void advancePastNextAttempt(RecruitmentCompletionCommand command) {
        LocalDateTime nextAttemptAt = reload(command).getNextAttemptAt();
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(nextAttemptAt)) {
            clock.advance(Duration.between(now, nextAttemptAt));
        }
    }

    private void assertAllRequestsCarry(RecruitmentCompletionCommand command, int expectedCount) {
        List<RecordedRequest> requests = SERVER.requests();
        assertThat(requests).hasSize(expectedCount).allSatisfy(request -> {
            assertThat(request.header("Idempotency-Key")).isEqualTo(command.getCommandId());
            assertThat(request.header("X-Job-Version")).isEqualTo(String.valueOf(command.getJobVersion()));
        });
    }

    private String claimAsCrashedExecutor(RecruitmentCompletionCommand command) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = storedNow();
        assertThat(transactionService.claim(command.getId(), token, now, now.plus(LEASE))).isPresent();
        return token;
    }

    private LockObservation observeLocks(RecruitmentCompletionCommand command) {
        try (Connection connection = openLockObserverConnection()) {
            int openTransactions = countOpenTransactions(connection);
            connection.setAutoCommit(false);
            try {
                boolean isJobRowFree = canLockNowait(connection, "job_posts", command.getJobPostId());
                boolean isCommandRowFree = canLockNowait(connection, "job_recruitment_completion_commands", command.getId());
                return new LockObservation(openTransactions, isJobRowFree, isCommandRowFree);
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int countOpenTransactions(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select count(*) from information_schema.innodb_trx where trx_mysql_thread_id <> connection_id()")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private boolean canLockNowait(Connection connection, String table, Long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from " + table + " where id = ? for update nowait")) {
            statement.setLong(1, id);
            statement.executeQuery().close();
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    private RecruitmentCompletionCommand closeJob() {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost().recruitCount(1).build());
        JobMatchingSeatReservation reservation = seatService.reserve(
                jobPost.getId(), nextId(), nextId(), 100L, "seat-" + UUID.randomUUID());
        seatService.confirm(jobPost.getId(), reservation.getId(), "confirm-" + UUID.randomUUID());
        return commandRepository.findByJobPostId(jobPost.getId()).get(0);
    }

    private RecruitmentCompletionCommand reload(RecruitmentCompletionCommand command) {
        return commandRepository.findById(command.getId()).orElseThrow();
    }

    private LocalDateTime storedNow() {
        return RecruitmentCompletionCommand.toStoredTime(LocalDateTime.now(clock));
    }

    private Long nextId() {
        return SEQUENCE.incrementAndGet();
    }

    private record LockObservation(int openTransactions, boolean isJobRowFree, boolean isCommandRowFree) {
    }
}
