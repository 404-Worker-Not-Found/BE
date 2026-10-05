package com.workernotfound.job.domain.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderFailureType;
import com.workernotfound.job.domain.job.exception.PaymentOrderCreationException;
import com.workernotfound.job.domain.job.port.PaymentOrderCreator;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.MutableClock;
import com.workernotfound.job.support.StubPaymentServer;
import com.workernotfound.job.support.StubPaymentServer.RecordedRequest;
import com.workernotfound.job.support.StubPaymentServer.StubResponse;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 주문 생성 명령의 전송·재시도·복구·주문 연결 검증. 명령 저장·실행권·결과 기록은 실제 MySQL로, HTTP는 실제 소켓의
 * payment-service 대역으로 확인한다. 실제 PG 결제나 payment-service 수신 처리는 사용하지 않는다.
 */
class PaymentOrderDispatchTests extends IntegrationTestSupport {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    private static final Duration CALL_TIMEOUT = Duration.ofMillis(1500);
    private static final Duration LEASE = Duration.ofSeconds(3);
    private static final Duration BASE_DELAY = Duration.ofSeconds(2);
    private static final Duration MAX_DELAY = Duration.ofSeconds(5);
    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong(930_000);
    private static final StubPaymentServer SERVER = StubPaymentServer.start();

    @Autowired
    private PaymentOrderDispatcher dispatcher;

    @Autowired
    private PaymentOrderCommandTransactionService transactionService;

    @Autowired
    private PaymentOrderCreator creator;

    @Autowired
    private JobCommandService jobCommandService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobPaymentOrderCommandRepository commandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MutableClock clock;

    @DynamicPropertySource
    static void registerPaymentServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("job.payment-service.base-url", SERVER::baseUrl);
        registry.add("job.payment-service.connect-timeout", () -> "500ms");
        registry.add("job.payment-service.read-timeout", () -> READ_TIMEOUT.toMillis() + "ms");
        registry.add("job.payment-service.call-timeout", () -> CALL_TIMEOUT.toMillis() + "ms");
        registry.add("job.payment-order.lease-duration", () -> LEASE.toSeconds() + "s");
        registry.add("job.payment-order.retry-base-delay", () -> BASE_DELAY.toSeconds() + "s");
        registry.add("job.payment-order.retry-max-delay", () -> MAX_DELAY.toSeconds() + "s");
    }

    @BeforeEach
    void setUp() {
        SERVER.reset();
        clock.fixAtNow();
        // 다른 테스트가 남긴 미완료 명령이 대역 서버 응답 순서를 소비하지 않도록 종료한다.
        jdbcTemplate.update("update job_payment_order_commands set status = 'SUPERSEDED' where status = 'PENDING'");
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
    void sendsStoredSnapshotAndLinksVerifiedOrderWithoutOpeningJob() {
        JobPaymentOrderCommand command = createJob();

        assertThat(dispatcher.dispatch(command.getId())).isTrue();

        String orderId = SERVER.issuedOrderId(command.getIdempotencyKey());
        assertThat(orderId).isNotNull();
        JobPaymentOrderCommand succeeded = reload(command);
        assertThat(succeeded.getStatus()).isEqualTo(PaymentOrderCommandStatus.SUCCEEDED);
        assertThat(succeeded.getOrderId()).isEqualTo(orderId);
        assertThat(succeeded.getLeaseToken()).isNull();
        JobPost jobPost = jobOf(command);
        assertThat(jobPost.getPaymentOrderId()).isEqualTo(orderId);
        assertThat(jobPost.getPaymentJobVersion()).isEqualTo(command.getJobVersion());
        assertThat(jobPost.getPaymentAmount()).isEqualTo(command.getAmount());
        assertThat(jobPost.getPaymentCurrency()).isEqualTo("KRW");
        // 주문 생성 완료만으로 공고를 공개하지 않는다.
        assertThat(jobPost.getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        assertAllRequestsCarry(command, 1);
    }

    @Test
    void holdsNoTransactionOrJobLockWhileWaitingForHttpResponse() {
        JobPaymentOrderCommand command = createJob();
        AtomicReference<LockObservation> observation = new AtomicReference<>();
        SERVER.onRequest(request -> observation.set(observeLocks(command)));

        dispatcher.dispatch(command.getId());

        assertThat(observation.get()).isEqualTo(new LockObservation(0, true, true));
        assertThat(reload(command).getStatus()).isEqualTo(PaymentOrderCommandStatus.SUCCEEDED);
    }

    @Test
    void lockObservationDetectsHeldJobLock() {
        JobPaymentOrderCommand command = createJob();

        // 대조군: 공고 행 잠금을 잡은 트랜잭션 안에서는 관찰 결과가 달라야 위 검증이 의미를 갖는다.
        LockObservation observation = transactionTemplate.execute(status -> {
            jobPostRepository.findByIdForUpdate(command.getJobPostId()).orElseThrow();
            return observeLocks(command);
        });

        assertThat(observation.openTransactions()).isPositive();
        assertThat(observation.isJobRowFree()).isFalse();
    }

    @Test
    void recoversOriginalOrderWithSameKeyAndSnapshotAfterLostResponse() {
        JobPaymentOrderCommand command = createJob();
        // payment-service는 주문을 만들었지만 응답이 전체 호출 제한시간 뒤에 도착한다.
        SERVER.enqueueProcessedButDelayed(CALL_TIMEOUT.multipliedBy(2));

        dispatchAndAssertFailure(command, PaymentOrderFailureType.TIMEOUT, BASE_DELAY);
        String originalOrderId = SERVER.issuedOrderId(command.getIdempotencyKey());
        assertThat(originalOrderId).isNotNull();
        assertThat(jobOf(command).getPaymentOrderId()).isNull();

        advancePastNextAttempt(command);
        assertThat(dispatcher.dispatch(command.getId())).isTrue();

        assertThat(SERVER.issuedOrderCount()).isOne();
        assertThat(jobOf(command).getPaymentOrderId()).isEqualTo(originalOrderId);
        assertAllRequestsCarry(command, 2);
    }

    @Test
    void retriesTimeoutServerErrorAndInvalidResponseWithoutRecordingSuccess() {
        JobPaymentOrderCommand command = createJob();
        SERVER.enqueue(
                StubResponse.error(503, "GLOBAL-503-001"),
                new StubResponse(200, "{\"success\":true,\"data\":null}"),
                StubResponse.partialSuccessBodyThenStall());

        dispatchAndAssertFailure(command, PaymentOrderFailureType.SERVER_ERROR, BASE_DELAY);
        assertThat(reload(command).getLastFailureCode()).isEqualTo("GLOBAL-503-001");
        advancePastNextAttempt(command);
        // 계약과 다른 성공 응답은 운영 확인 대상이라 최대 지연으로만 다시 확인한다.
        dispatchAndAssertFailure(command, PaymentOrderFailureType.CONTRACT, MAX_DELAY);
        advancePastNextAttempt(command);
        // 성공 상태 헤더 뒤 본문을 받다 멈춘 응답도 성공이 아니다. 받은 상태는 진단 정보로만 남는다.
        // 세 번째 시도의 backoff(기본 2초 × 4)는 최대 지연(5초)으로 제한된다.
        dispatchAndAssertFailure(command, PaymentOrderFailureType.TIMEOUT, MAX_DELAY);
        assertThat(reload(command).getLastFailureHttpStatus()).isEqualTo(200);
        assertThat(jobOf(command).getPaymentOrderId()).isNull();

        advancePastNextAttempt(command);
        assertThat(dispatcher.dispatch(command.getId())).isTrue();

        assertThat(jobOf(command).getPaymentOrderId()).isEqualTo(SERVER.issuedOrderId(command.getIdempotencyKey()));
        assertThat(reload(command).getAttemptCount()).isEqualTo(4);
        assertAllRequestsCarry(command, 4);
    }

    @Test
    void doesNotLinkOrderWhoseSnapshotDiffersFromRequest() {
        JobPaymentOrderCommand command = createJob();
        SERVER.enqueue(StubResponse.order(UUID.randomUUID().toString(), command.getJobPostId(),
                command.getJobVersion(), (command.getAmount() + 1) + ".00", "KRW", "READY"));

        dispatchAndAssertFailure(command, PaymentOrderFailureType.SNAPSHOT_MISMATCH, MAX_DELAY);

        assertThat(jobOf(command).getPaymentOrderId()).isNull();
        assertThat(reload(command).getOrderId()).isNull();
    }

    @Test
    void keepsAuthenticationFailurePendingAndRecoversAfterFix() {
        JobPaymentOrderCommand command = createJob();
        SERVER.enqueue(StubResponse.error(401, "GLOBAL-401-001"));

        dispatchAndAssertFailure(command, PaymentOrderFailureType.AUTHENTICATION, MAX_DELAY);
        clock.advance(MAX_DELAY);
        assertThat(dispatcher.dispatchDue()).isOne();

        assertThat(reload(command).getStatus()).isEqualTo(PaymentOrderCommandStatus.SUCCEEDED);
        assertAllRequestsCarry(command, 2);
    }

    @Test
    void linksSameOrderWhenIdempotentReplayReturnsProgressedStatus() {
        JobPaymentOrderCommand command = createJob();
        SERVER.enqueueProcessedButDelayed(CALL_TIMEOUT.multipliedBy(2));
        dispatchAndAssertFailure(command, PaymentOrderFailureType.TIMEOUT, BASE_DELAY);
        // 응답을 받지 못한 사이 점주가 결제를 끝내 주문이 DEPOSITED가 됐다.
        SERVER.orderStatus("DEPOSITED");

        advancePastNextAttempt(command);
        assertThat(dispatcher.dispatch(command.getId())).isTrue();

        JobPost jobPost = jobOf(command);
        assertThat(jobPost.getPaymentOrderId()).isEqualTo(SERVER.issuedOrderId(command.getIdempotencyKey()));
        // 예치 상태 수신과 공개 전환은 후속 작업이다. 주문 응답 상태로 공고를 공개하지 않는다.
        assertThat(jobPost.getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
    }

    @Test
    void doesNotLinkSupersededOrder() {
        JobPaymentOrderCommand command = createJob();
        SERVER.orderStatus("SUPERSEDED");

        dispatchAndAssertFailure(command, PaymentOrderFailureType.ORDER_SUPERSEDED, MAX_DELAY);

        assertThat(jobOf(command).getPaymentOrderId()).isNull();
    }

    @Test
    void retriesWithOriginalPaymentVersionAfterJobVersionIncreases() {
        JobPaymentOrderCommand command = createJob();
        SERVER.enqueue(StubResponse.error(500, "GLOBAL-500-001"));
        dispatchAndAssertFailure(command, PaymentOrderFailureType.SERVER_ERROR, BASE_DELAY);
        // 결제와 무관한 변경으로 현재 @Version이 증가한다.
        Long versionBefore = jobOf(command).getVersion();
        changeJobPost(command.getJobPostId());
        assertThat(jobOf(command).getVersion()).isGreaterThan(versionBefore);

        advancePastNextAttempt(command);
        assertThat(dispatcher.dispatch(command.getId())).isTrue();

        JobPost jobPost = jobOf(command);
        assertThat(jobPost.getPaymentJobVersion()).isEqualTo(command.getJobVersion()).isEqualTo(1L);
        assertThat(jobPost.getVersion()).isGreaterThan(command.getJobVersion());
        assertAllRequestsCarry(command, 2);
        assertThat(SERVER.requests()).allSatisfy(request ->
                assertThat(request.jsonBody().path("jobVersion").longValue()).isEqualTo(1L));
    }

    @Test
    void concurrentExecutorsSendCommandOnce() throws Exception {
        JobPaymentOrderCommand command = createJob();
        SERVER.enqueue(request -> StubResponse.order(UUID.randomUUID().toString(), command.getJobPostId(),
                        command.getJobVersion(), command.getAmount() + ".00", "KRW", "READY")
                .delayedBy(Duration.ofMillis(200)));
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
        assertThat(reload(command).getStatus()).isEqualTo(PaymentOrderCommandStatus.SUCCEEDED);
    }

    @Test
    void takesOverExpiredLeaseAndIgnoresLateResultFromPreviousExecutor() {
        JobPaymentOrderCommand command = createJob();
        // 실행권만 얻고 멈춘 실행자
        String staleToken = claimAsCrashedExecutor(command);
        assertThat(dispatcher.dispatch(command.getId())).isFalse();
        assertThat(dispatcher.dispatchDue()).isZero();

        clock.advance(LEASE);
        String staleOrderId = UUID.randomUUID().toString();
        AtomicReference<PaymentOrderLinkResult> lateResult = new AtomicReference<>();
        // 새 실행자가 응답을 기다리는 동안 이전 실행자의 늦은 성공 결과가 도착한다.
        SERVER.onRequest(request -> lateResult.set(
                transactionService.recordCreated(command.getId(), staleToken, staleOrderId, storedNow())));
        assertThat(dispatcher.dispatch(command.getId())).isTrue();

        assertThat(lateResult.get()).isEqualTo(PaymentOrderLinkResult.LEASE_LOST);
        String orderId = SERVER.issuedOrderId(command.getIdempotencyKey());
        assertThat(jobOf(command).getPaymentOrderId()).isEqualTo(orderId);
        // 성공 이후 도착한 이전 실행자의 늦은 결과도 반영되지 않는다.
        assertThat(transactionService.recordCreated(command.getId(), staleToken, staleOrderId, storedNow()))
                .isEqualTo(PaymentOrderLinkResult.LEASE_LOST);
        assertThat(transactionService.markFailed(command.getId(), staleToken, new PaymentOrderCreationException(
                PaymentOrderFailureType.NETWORK, null, (String) null), storedNow(), storedNow())).isFalse();
        assertThat(jobOf(command).getPaymentOrderId()).isEqualTo(orderId);
        assertThat(reload(command).getOrderId()).isEqualTo(orderId);
        assertThat(reload(command).getStatus()).isEqualTo(PaymentOrderCommandStatus.SUCCEEDED);
    }

    @Test
    void recoversWhenProcessStoppedAfterHttpSuccessBeforeLocalRecord() {
        JobPaymentOrderCommand command = createJob();
        claimAsCrashedExecutor(command);
        // 실행권을 가진 실행자가 주문 생성 응답까지 받은 뒤 결과를 기록하지 못하고 멈췄다.
        creator.createOrder(PaymentOrderDispatch.from(reload(command)).request());
        assertThat(jobOf(command).getPaymentOrderId()).isNull();
        String originalOrderId = SERVER.issuedOrderId(command.getIdempotencyKey());

        clock.advance(LEASE);
        assertThat(dispatcher.dispatchDue()).isOne();

        assertThat(jobOf(command).getPaymentOrderId()).isEqualTo(originalOrderId);
        assertThat(SERVER.issuedOrderCount()).isOne();
        assertAllRequestsCarry(command, 2);
    }

    @Test
    void olderCommandCannotOverwriteLatestOrderLink() {
        JobPaymentOrderCommand first = createJob();
        // 후속 재결제 기능이 발급할 다음 순번 명령을 흉내 낸다.
        JobPaymentOrderCommand second = issueNextCommand(first);

        assertThat(dispatcher.dispatch(first.getId())).isTrue();
        assertThat(reload(first).getStatus()).isEqualTo(PaymentOrderCommandStatus.SUPERSEDED);
        assertThat(reload(first).getOrderId()).isNull();
        assertThat(jobOf(first).getPaymentOrderId()).isNull();

        assertThat(dispatcher.dispatch(second.getId())).isTrue();
        String latestOrderId = SERVER.issuedOrderId(second.getIdempotencyKey());
        assertThat(jobOf(first).getPaymentOrderId()).isEqualTo(latestOrderId);
        assertThat(latestOrderId).isNotEqualTo(SERVER.issuedOrderId(first.getIdempotencyKey()));
        // 종료된 과거 명령은 다시 전송되지 않는다.
        clock.advance(MAX_DELAY);
        assertThat(dispatcher.dispatch(first.getId())).isFalse();
    }

    @Test
    void schedulerPathSendsCommittedCommand() {
        JobPaymentOrderCommand command = createJob();

        assertThat(dispatcher.dispatchDue()).isOne();

        assertThat(reload(command).getStatus()).isEqualTo(PaymentOrderCommandStatus.SUCCEEDED);
    }

    private void dispatchAndAssertFailure(
            JobPaymentOrderCommand command,
            PaymentOrderFailureType expectedType,
            Duration expectedDelay
    ) {
        LocalDateTime now = storedNow();
        assertThat(dispatcher.dispatch(command.getId())).isTrue();
        JobPaymentOrderCommand failed = reload(command);
        assertThat(failed.getStatus()).isEqualTo(PaymentOrderCommandStatus.PENDING);
        assertThat(failed.getLastFailureType()).isEqualTo(expectedType);
        assertThat(failed.getOrderId()).isNull();
        assertThat(failed.getLeaseToken()).isNull();
        assertThat(failed.getNextAttemptAt()).isEqualTo(now.plus(expectedDelay));
    }

    private void advancePastNextAttempt(JobPaymentOrderCommand command) {
        LocalDateTime nextAttemptAt = reload(command).getNextAttemptAt();
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(nextAttemptAt)) {
            clock.advance(Duration.between(now, nextAttemptAt));
        }
    }

    // 모든 시도가 같은 멱등 키와 발급 당시 스냅샷을 그대로 보낸다.
    private void assertAllRequestsCarry(JobPaymentOrderCommand command, int expectedCount) {
        List<RecordedRequest> requests = SERVER.requests();
        assertThat(requests).hasSize(expectedCount).allSatisfy(request -> {
            assertThat(request.header("Idempotency-Key")).isEqualTo(command.getIdempotencyKey());
            assertThat(request.header("X-Internal-Secret")).isEqualTo("test-internal-secret");
            JsonNode body = request.jsonBody();
            assertThat(body.path("jobPostId").longValue()).isEqualTo(command.getJobPostId());
            assertThat(body.path("jobVersion").longValue()).isEqualTo(command.getJobVersion());
            assertThat(body.path("ownerMemberId").longValue()).isEqualTo(command.getOwnerMemberId());
            assertThat(body.path("amount").longValue()).isEqualTo(command.getAmount());
            assertThat(body.path("currency").textValue()).isEqualTo("KRW");
        });
        assertThat(requests.stream().map(RecordedRequest::body).distinct()).hasSize(1);
    }

    private String claimAsCrashedExecutor(JobPaymentOrderCommand command) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = storedNow();
        assertThat(transactionService.claim(command.getId(), token, now, now.plus(LEASE))).isPresent();
        return token;
    }

    private JobPaymentOrderCommand issueNextCommand(JobPaymentOrderCommand previous) {
        return commandRepository.saveAndFlush(JobPaymentOrderCommand.builder()
                .jobPostId(previous.getJobPostId())
                .issueSequence(previous.getIssueSequence() + 1)
                .idempotencyKey(UUID.randomUUID().toString())
                .jobVersion(previous.getJobVersion())
                .ownerMemberId(previous.getOwnerMemberId())
                .amount(previous.getAmount())
                .currency(previous.getCurrency())
                .nextAttemptAt(LocalDateTime.now(clock))
                .build());
    }

    private void changeJobPost(Long jobPostId) {
        transactionTemplate.executeWithoutResult(status -> {
            JobPost jobPost = jobPostRepository.findById(jobPostId).orElseThrow();
            ReflectionTestUtils.setField(jobPost, "description", "결제와 무관한 설명 변경");
        });
    }

    private LockObservation observeLocks(JobPaymentOrderCommand command) {
        try (Connection connection = openLockObserverConnection()) {
            int openTransactions = countOpenTransactions(connection);
            connection.setAutoCommit(false);
            try {
                boolean isJobRowFree = canLockNowait(connection, "job_posts", command.getJobPostId());
                boolean isCommandRowFree = canLockNowait(connection, "job_payment_order_commands", command.getId());
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

    private JobPaymentOrderCommand createJob() {
        LocalDate workDate = LocalDate.now().plusDays(2);
        Long jobPostId = jobCommandService.create(OWNER_SEQUENCE.incrementAndGet(), new CreateJobRequest(
                1L, 1L, "테스트 상점", "서울시 마포구", "결제 전송 공고", "설명",
                workDate, LocalTime.of(9, 0), LocalTime.of(18, 0), false, 10_320, null, 2,
                new BigDecimal("37.5665000"), new BigDecimal("126.9780000"), "MEDIUM",
                LocalDateTime.of(workDate.minusDays(1), LocalTime.NOON)));
        return commandRepository.findByJobPostIdOrderByIssueSequenceAsc(jobPostId).get(0);
    }

    private JobPaymentOrderCommand reload(JobPaymentOrderCommand command) {
        return commandRepository.findById(command.getId()).orElseThrow();
    }

    private JobPost jobOf(JobPaymentOrderCommand command) {
        return jobPostRepository.findById(command.getJobPostId()).orElseThrow();
    }

    private LocalDateTime storedNow() {
        return JobPaymentOrderCommand.toStoredTime(LocalDateTime.now(clock));
    }

    private record LockObservation(int openTransactions, boolean isJobRowFree, boolean isCommandRowFree) {
    }
}
