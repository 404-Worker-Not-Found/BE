package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobApplicationAdmission;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.AdmissionConsumptionResult;
import com.workernotfound.job.domain.job.entity.enums.ApplicationAdmissionStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobApplicationAdmissionRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class JobApplicationAdmissionConsumptionServiceTests extends IntegrationTestSupport {

    private static final Long WORKER_ID = 100L;
    // 저장 정밀도(마이크로초)에 맞춘 만료 시각. 판단은 이벤트 발생 시각만 쓰므로 현재 시각과 무관하다.
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2030, 1, 1, 12, 0, 0, 123_456_000);

    private static final String ADMISSION_LOCK_WAIT_QUERY = """
            SELECT 1
            FROM performance_schema.data_lock_waits waits
            JOIN performance_schema.data_locks requested
              ON requested.ENGINE = waits.ENGINE
             AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
            JOIN performance_schema.threads requester
              ON requester.THREAD_ID = waits.REQUESTING_THREAD_ID
            JOIN performance_schema.threads blocker
              ON blocker.THREAD_ID = waits.BLOCKING_THREAD_ID
            WHERE requester.PROCESSLIST_ID = ?
              AND blocker.PROCESSLIST_ID = ?
              AND requested.OBJECT_SCHEMA = ?
              AND requested.OBJECT_NAME = 'job_application_admissions'
              AND requested.INDEX_NAME = 'PRIMARY'
              AND requested.LOCK_STATUS = 'WAITING'
            """;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JobApplicationAdmissionConsumptionService consumptionService;

    @Autowired
    private JobApplicationAdmissionCommandService admissionCommandService;

    @Autowired
    private JobApplicationAdmissionRepository admissionRepository;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterAll
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void consumesReservedAdmissionSubmittedBeforeExpiry() {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.RESERVED);
        LocalDateTime occurredAt = EXPIRES_AT.minusMinutes(1);

        AdmissionConsumptionResult result = consumptionService.consume(commandFor(admission, occurredAt));

        assertThat(result).isEqualTo(AdmissionConsumptionResult.CONSUMED);
        assertConsumedAt(admission.getId(), occurredAt);
    }

    @Test
    void consumesExpiredAdmissionWhenSubmissionPrecededExpiry() {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.EXPIRED);
        LocalDateTime occurredAt = EXPIRES_AT.minusSeconds(30);

        AdmissionConsumptionResult result = consumptionService.consume(commandFor(admission, occurredAt));

        assertThat(result).isEqualTo(AdmissionConsumptionResult.CONSUMED);
        assertConsumedAt(admission.getId(), occurredAt);
    }

    @Test
    void consumesSubmissionOneMicrosecondBeforeExpiry() {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.RESERVED);
        LocalDateTime occurredAt = EXPIRES_AT.minusNanos(1_000);

        assertThat(consumptionService.consume(commandFor(admission, occurredAt)))
                .isEqualTo(AdmissionConsumptionResult.CONSUMED);
        assertConsumedAt(admission.getId(), occurredAt);
    }

    // 나노초 값을 그대로 저장하면 MySQL 반올림으로 consumedAt이 만료 시각과 같아진다. 절삭한 값이 저장되어야 한다.
    @Test
    void storesSubMicrosecondSubmissionTruncatedBeforeExpiry() {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.RESERVED);

        assertThat(consumptionService.consume(commandFor(admission, EXPIRES_AT.minusNanos(1))))
                .isEqualTo(AdmissionConsumptionResult.CONSUMED);
        assertConsumedAt(admission.getId(), EXPIRES_AT.minusNanos(1_000));
    }

    @Test
    void doesNotConsumeSubmissionAtExpiry() {
        JobApplicationAdmission reserved = saveAdmission(ApplicationAdmissionStatus.RESERVED);
        JobApplicationAdmission expired = saveAdmission(ApplicationAdmissionStatus.EXPIRED);

        assertThat(consumptionService.consume(commandFor(reserved, EXPIRES_AT)))
                .isEqualTo(AdmissionConsumptionResult.SUBMITTED_AFTER_EXPIRY);
        assertThat(consumptionService.consume(commandFor(expired, EXPIRES_AT)))
                .isEqualTo(AdmissionConsumptionResult.SUBMITTED_AFTER_EXPIRY);

        assertUnchanged(reserved.getId(), ApplicationAdmissionStatus.RESERVED);
        assertUnchanged(expired.getId(), ApplicationAdmissionStatus.EXPIRED);
    }

    @Test
    void doesNotConsumeSubmissionAfterExpiry() {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.RESERVED);

        assertThat(consumptionService.consume(commandFor(admission, EXPIRES_AT.plusNanos(1))))
                .isEqualTo(AdmissionConsumptionResult.SUBMITTED_AFTER_EXPIRY);
        assertThat(consumptionService.consume(commandFor(admission, EXPIRES_AT.plusMinutes(1))))
                .isEqualTo(AdmissionConsumptionResult.SUBMITTED_AFTER_EXPIRY);

        assertUnchanged(admission.getId(), ApplicationAdmissionStatus.RESERVED);
    }

    @Test
    void repeatedProcessingKeepsFirstConsumedAt() {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.RESERVED);
        LocalDateTime firstOccurredAt = EXPIRES_AT.minusMinutes(2);
        consumptionService.consume(commandFor(admission, firstOccurredAt));

        AdmissionConsumptionResult sameEvent = consumptionService.consume(commandFor(admission, firstOccurredAt));
        AdmissionConsumptionResult laterEvent = consumptionService.consume(commandFor(admission, EXPIRES_AT.minusMinutes(1)));
        AdmissionConsumptionResult afterExpiry = consumptionService.consume(commandFor(admission, EXPIRES_AT.plusMinutes(1)));

        assertThat(sameEvent).isEqualTo(AdmissionConsumptionResult.ALREADY_CONSUMED);
        assertThat(laterEvent).isEqualTo(AdmissionConsumptionResult.ALREADY_CONSUMED);
        assertThat(afterExpiry).isEqualTo(AdmissionConsumptionResult.ALREADY_CONSUMED);
        assertConsumedAt(admission.getId(), firstOccurredAt);
    }

    @Test
    void rejectsMissingAdmissionIdAndOccurredAt() {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.RESERVED);

        AdmissionConsumptionResult missingId = consumptionService.consume(new AdmissionConsumptionCommand(
                null, admission.getJobPostId(), WORKER_ID, EXPIRES_AT.minusMinutes(1)));
        AdmissionConsumptionResult missingOccurredAt = consumptionService.consume(commandFor(admission, null));

        assertThat(missingId).isEqualTo(AdmissionConsumptionResult.REJECTED_MISSING_ADMISSION_ID);
        assertThat(missingOccurredAt).isEqualTo(AdmissionConsumptionResult.REJECTED_MISSING_OCCURRED_AT);
        assertUnchanged(admission.getId(), ApplicationAdmissionStatus.RESERVED);
    }

    @Test
    void rejectsUnknownAdmission() {
        AdmissionConsumptionResult result = consumptionService.consume(
                new AdmissionConsumptionCommand(999_999_999L, 1L, WORKER_ID, EXPIRES_AT.minusMinutes(1)));

        assertThat(result).isEqualTo(AdmissionConsumptionResult.REJECTED_ADMISSION_NOT_FOUND);
    }

    @Test
    void rejectsJobPostOrWorkerMismatchWithoutChangingAdmission() {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.RESERVED);
        LocalDateTime occurredAt = EXPIRES_AT.minusMinutes(1);

        AdmissionConsumptionResult otherJob = consumptionService.consume(new AdmissionConsumptionCommand(
                admission.getId(), admission.getJobPostId() + 1, WORKER_ID, occurredAt));
        AdmissionConsumptionResult missingJob = consumptionService.consume(new AdmissionConsumptionCommand(
                admission.getId(), null, WORKER_ID, occurredAt));
        AdmissionConsumptionResult otherWorker = consumptionService.consume(new AdmissionConsumptionCommand(
                admission.getId(), admission.getJobPostId(), WORKER_ID + 1, occurredAt));
        AdmissionConsumptionResult missingWorker = consumptionService.consume(new AdmissionConsumptionCommand(
                admission.getId(), admission.getJobPostId(), null, occurredAt));

        assertThat(otherJob).isEqualTo(AdmissionConsumptionResult.REJECTED_JOB_POST_MISMATCH);
        assertThat(missingJob).isEqualTo(AdmissionConsumptionResult.REJECTED_JOB_POST_MISMATCH);
        assertThat(otherWorker).isEqualTo(AdmissionConsumptionResult.REJECTED_WORKER_MISMATCH);
        assertThat(missingWorker).isEqualTo(AdmissionConsumptionResult.REJECTED_WORKER_MISMATCH);
        assertUnchanged(admission.getId(), ApplicationAdmissionStatus.RESERVED);
    }

    // 이미 사용된 승인이라도 다른 지원의 이벤트는 재전송으로 보지 않고 거절한다.
    @Test
    void rejectsMismatchOnConsumedAdmissionBeforeReportingAlreadyConsumed() {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.RESERVED);
        LocalDateTime occurredAt = EXPIRES_AT.minusMinutes(1);
        consumptionService.consume(commandFor(admission, occurredAt));

        AdmissionConsumptionResult result = consumptionService.consume(new AdmissionConsumptionCommand(
                admission.getId(), admission.getJobPostId(), WORKER_ID + 1, occurredAt));

        assertThat(result).isEqualTo(AdmissionConsumptionResult.REJECTED_WORKER_MISMATCH);
        assertConsumedAt(admission.getId(), occurredAt);
    }

    @Test
    void classifiesOnlyContractViolationsAsRejected() {
        assertThat(AdmissionConsumptionResult.values())
                .filteredOn(AdmissionConsumptionResult::isRejected)
                .containsExactlyInAnyOrder(
                        AdmissionConsumptionResult.REJECTED_MISSING_ADMISSION_ID,
                        AdmissionConsumptionResult.REJECTED_MISSING_OCCURRED_AT,
                        AdmissionConsumptionResult.REJECTED_ADMISSION_NOT_FOUND,
                        AdmissionConsumptionResult.REJECTED_JOB_POST_MISMATCH,
                        AdmissionConsumptionResult.REJECTED_WORKER_MISMATCH
                );
    }

    // 소비자가 이후 단계(수신 기록 등)에서 실패해 트랜잭션이 롤백되면 사용 완료도 함께 취소되어 재처리할 수 있어야 한다.
    @Test
    void rollsBackConsumptionWithEnclosingTransaction() {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.RESERVED);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            consumptionService.consume(commandFor(admission, EXPIRES_AT.minusMinutes(1)));
            throw new IllegalStateException("이후 단계 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertUnchanged(admission.getId(), ApplicationAdmissionStatus.RESERVED);
        assertThat(consumptionService.consume(commandFor(admission, EXPIRES_AT.minusMinutes(1))))
                .isEqualTo(AdmissionConsumptionResult.CONSUMED);
    }

    @Test
    void concurrentConsumptionWaitsForRowLockAndKeepsFirstCommit() throws Exception {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.RESERVED);
        LocalDateTime firstOccurredAt = EXPIRES_AT.minusMinutes(3);

        AdmissionRowLockRace race = new AdmissionRowLockRace();
        AdmissionConsumptionResult first = race.run(
                admission.getId(),
                () -> consumptionService.consume(commandFor(admission, firstOccurredAt)),
                () -> consumptionService.consume(commandFor(admission, EXPIRES_AT.minusMinutes(1))),
                false
        );

        assertThat(first).isEqualTo(AdmissionConsumptionResult.CONSUMED);
        assertThat(race.contender().get()).isEqualTo(AdmissionConsumptionResult.ALREADY_CONSUMED);
        assertConsumedAt(admission.getId(), firstOccurredAt);
    }

    @Test
    void contenderConsumesAfterLockHolderRollsBack() throws Exception {
        JobApplicationAdmission admission = saveAdmission(ApplicationAdmissionStatus.RESERVED);
        LocalDateTime contenderOccurredAt = EXPIRES_AT.minusMinutes(1);

        AdmissionRowLockRace race = new AdmissionRowLockRace();
        race.run(
                admission.getId(),
                () -> consumptionService.consume(commandFor(admission, EXPIRES_AT.minusMinutes(3))),
                () -> consumptionService.consume(commandFor(admission, contenderOccurredAt)),
                true
        );

        assertThat(race.contender().get()).isEqualTo(AdmissionConsumptionResult.CONSUMED);
        assertConsumedAt(admission.getId(), contenderOccurredAt);
    }

    @Test
    void consumesIssuedAdmissionAndKeepsSameKeyRetryContract() {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().build()));
        String key = "consume-" + UUID.randomUUID();
        JobApplicationAdmission issued = admissionCommandService.create(jobPost.getId(), WORKER_ID, key);

        JobApplicationAdmission retried = admissionCommandService.create(jobPost.getId(), WORKER_ID, key);
        assertThat(retried.getId()).isEqualTo(issued.getId());

        AdmissionConsumptionResult result = consumptionService.consume(new AdmissionConsumptionCommand(
                issued.getId(), jobPost.getId(), WORKER_ID, issued.getAdmittedAt().plusSeconds(1)));
        assertThat(result).isEqualTo(AdmissionConsumptionResult.CONSUMED);

        // RESERVED가 아닌 승인의 같은 키 재요청은 기존 계약대로 JOB-409-003이다.
        assertThatThrownBy(() -> admissionCommandService.create(jobPost.getId(), WORKER_ID, key))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(JobErrorCode.ADMISSION_EXPIRED));
        assertThatThrownBy(() -> admissionCommandService.create(jobPost.getId(), WORKER_ID + 1, key))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(JobErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    private AdmissionConsumptionCommand commandFor(JobApplicationAdmission admission, LocalDateTime occurredAt) {
        return new AdmissionConsumptionCommand(
                admission.getId(), admission.getJobPostId(), admission.getWorkerMemberId(), occurredAt);
    }

    private JobApplicationAdmission saveAdmission(ApplicationAdmissionStatus status) {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().build()));
        JobApplicationAdmission admission = JobApplicationAdmission.builder()
                .jobPostId(jobPost.getId())
                .workerMemberId(WORKER_ID)
                .idempotencyKey("consume-" + UUID.randomUUID())
                .jobVersion(jobPost.getVersion())
                .ownerMemberId(jobPost.getOwnerId())
                .categoryId(jobPost.getCategoryId())
                .workDate(jobPost.getWorkDate())
                .startTime(jobPost.getStartTime())
                .endTime(jobPost.getEndTime())
                .latitude(jobPost.getLatitude())
                .longitude(jobPost.getLongitude())
                .admittedAt(EXPIRES_AT.minusMinutes(5))
                .expiresAt(EXPIRES_AT)
                .build();
        // 만료 상태 정리는 이번 범위가 아니므로 EXPIRED 승인은 상태를 직접 지정한다.
        ReflectionTestUtils.setField(admission, "status", status);
        return admissionRepository.save(admission);
    }

    private void assertConsumedAt(Long admissionId, LocalDateTime expectedConsumedAt) {
        JobApplicationAdmission stored = admissionRepository.findById(admissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ApplicationAdmissionStatus.CONSUMED);
        assertThat(stored.getConsumedAt()).isEqualTo(expectedConsumedAt);
        assertThat(stored.getConsumedAt()).isBefore(stored.getExpiresAt());
    }

    private void assertUnchanged(Long admissionId, ApplicationAdmissionStatus expectedStatus) {
        JobApplicationAdmission stored = admissionRepository.findById(admissionId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(expectedStatus);
        assertThat(stored.getConsumedAt()).isNull();
    }

    /**
     * 첫 처리를 커밋(또는 롤백) 직전에 멈추고, 경쟁 처리가 같은 승인 행 잠금을 기다리는지 확인한 뒤 첫 처리를 끝낸다.
     */
    private final class AdmissionRowLockRace {

        private final CountDownLatch firstWritten = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CompletableFuture<Long> contenderConnectionId = new CompletableFuture<>();
        private volatile long firstConnectionId;
        private Future<AdmissionConsumptionResult> contender;

        AdmissionConsumptionResult run(
                Long admissionId,
                Supplier<AdmissionConsumptionResult> first,
                Supplier<AdmissionConsumptionResult> contending,
                boolean rollbackFirst
        ) throws Exception {
            Future<AdmissionConsumptionResult> firstResult =
                    executor.submit(() -> holdBeforeCompletion(first, rollbackFirst));
            try {
                assertThat(firstWritten.await(30, TimeUnit.SECONDS)).isTrue();
                contender = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    contenderConnectionId.complete(currentConnectionId());
                    return contending.get();
                }));
                assertWaitingForAdmissionLock();
                releaseFirst.countDown();
                contender.get(10, TimeUnit.SECONDS);
                return firstResult.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                if (rollbackFirst && exception.getCause() instanceof RollbackRequested) {
                    return null;
                }
                throw exception;
            } finally {
                releaseFirst.countDown();
            }
        }

        Future<AdmissionConsumptionResult> contender() {
            return contender;
        }

        private AdmissionConsumptionResult holdBeforeCompletion(
                Supplier<AdmissionConsumptionResult> operation,
                boolean rollback
        ) {
            return new TransactionTemplate(transactionManager).execute(status -> {
                firstConnectionId = currentConnectionId();
                AdmissionConsumptionResult result = operation.get();
                entityManager.flush();
                firstWritten.countDown();
                try {
                    assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                if (rollback) {
                    throw new RollbackRequested();
                }
                return result;
            });
        }

        private long currentConnectionId() {
            return ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue();
        }

        private void assertWaitingForAdmissionLock() throws Exception {
            long connectionId = contenderConnectionId.get(10, TimeUnit.SECONDS);
            try (Connection observer = openLockObserverConnection();
                    PreparedStatement query = observer.prepareStatement(ADMISSION_LOCK_WAIT_QUERY)) {
                query.setLong(1, connectionId);
                query.setLong(2, firstConnectionId);
                query.setString(3, observer.getCatalog());
                await().alias("경쟁 처리가 첫 처리의 승인 행 잠금을 기다려야 한다")
                        .pollInSameThread().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                        .until(() -> {
                            assertThat(contender.isDone()).as("잠금 관찰 전에 경쟁 처리가 끝나면 안 된다").isFalse();
                            try (ResultSet waiting = query.executeQuery()) {
                                return waiting.next();
                            }
                        });
            }
        }
    }

    private static final class RollbackRequested extends RuntimeException {
    }
}
