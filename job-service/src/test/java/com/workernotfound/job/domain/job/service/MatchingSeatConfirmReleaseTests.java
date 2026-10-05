package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingSeatConfirmReleaseTests extends IntegrationTestSupport {

    private static final AtomicLong SEQUENCE = new AtomicLong(200_000);

    @Autowired
    private MatchingSeatReservationCommandService service;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobMatchingSeatReservationRepository reservationRepository;

    @Autowired
    private MutableClock clock;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(2);
        clock.fixAtNow();
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
        clock.reset();
    }

    @Test
    void confirmsReservedSeatAndRepeatsWithSameKey() {
        JobMatchingSeatReservation reservation = reserve(saveJob(1));
        String key = newKey();

        JobMatchingSeatReservation first = service.confirm(reservation.getJobPostId(), reservation.getId(), key);
        JobMatchingSeatReservation second = service.confirm(reservation.getJobPostId(), reservation.getId(), key);

        assertThat(first.getStatus()).isEqualTo(MatchingSeatReservationStatus.CONSUMED);
        assertThat(second.getStatus()).isEqualTo(MatchingSeatReservationStatus.CONSUMED);
        assertThat(second.getConsumedAt()).isEqualTo(first.getConsumedAt());
        assertThat(reload(reservation).getConfirmIdempotencyKey()).isEqualTo(key);
    }

    @Test
    void retriesLostConfirmResponseAfterOriginalExpiry() {
        JobMatchingSeatReservation reservation = reserve(saveJob(1));
        String key = newKey();
        service.confirm(reservation.getJobPostId(), reservation.getId(), key);

        clock.advance(Duration.ofMinutes(30));
        service.expireOverdue(reservation.getJobPostId());

        assertThat(service.confirm(reservation.getJobPostId(), reservation.getId(), key).getStatus())
                .isEqualTo(MatchingSeatReservationStatus.CONSUMED);
    }

    @Test
    void doesNotOverwriteConfirmKeyOrAcceptDifferentKeyAfterConsumption() {
        JobMatchingSeatReservation reservation = reserve(saveJob(1));
        String key = newKey();
        service.confirm(reservation.getJobPostId(), reservation.getId(), key);

        assertError(() -> service.confirm(reservation.getJobPostId(), reservation.getId(), newKey()),
                JobErrorCode.SEAT_RESERVATION_STATE_CONFLICT);
        assertThat(reload(reservation).getConfirmIdempotencyKey()).isEqualTo(key);
    }

    @Test
    void rejectsConfirmKeyReusedForAnotherReservation() {
        JobPost jobPost = saveJob(2);
        JobMatchingSeatReservation first = reserve(jobPost);
        JobMatchingSeatReservation second = reserve(jobPost);
        String key = newKey();
        service.confirm(jobPost.getId(), first.getId(), key);

        assertError(() -> service.confirm(jobPost.getId(), second.getId(), key), JobErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(reload(second).getStatus()).isEqualTo(MatchingSeatReservationStatus.RESERVED);
    }

    @Test
    void rejectsConfirmOfExpiredReleasedOrForeignReservation() {
        JobPost jobPost = saveJob(3);
        JobMatchingSeatReservation expiring = reserve(jobPost);
        JobMatchingSeatReservation released = reserve(jobPost);
        service.release(jobPost.getId(), released.getId(), newKey());
        JobPost otherJob = saveJob(1);

        assertError(() -> service.confirm(otherJob.getId(), expiring.getId(), newKey()),
                JobErrorCode.SEAT_RESERVATION_NOT_FOUND);
        assertError(() -> service.confirm(jobPost.getId(), released.getId(), newKey()),
                JobErrorCode.SEAT_RESERVATION_STATE_CONFLICT);

        // 만료 처리 전이라도 잠금 후 현재 시각이 expiresAt 이상이면 확정하지 않는다.
        clock.advance(Duration.ofMinutes(10));
        assertError(() -> service.confirm(jobPost.getId(), expiring.getId(), newKey()),
                JobErrorCode.SEAT_RESERVATION_EXPIRED);
        service.expireOverdue(jobPost.getId());
        assertError(() -> service.confirm(jobPost.getId(), expiring.getId(), newKey()),
                JobErrorCode.SEAT_RESERVATION_EXPIRED);
    }

    @Test
    void releasesReservedSeatAndRepeatsWithSameKeyOnly() {
        JobMatchingSeatReservation reservation = reserve(saveJob(1));
        String key = newKey();

        service.release(reservation.getJobPostId(), reservation.getId(), key);
        JobMatchingSeatReservation repeated = service.release(reservation.getJobPostId(), reservation.getId(), key);

        assertThat(repeated.getStatus()).isEqualTo(MatchingSeatReservationStatus.RELEASED);
        assertError(() -> service.release(reservation.getJobPostId(), reservation.getId(), newKey()),
                JobErrorCode.SEAT_RESERVATION_STATE_CONFLICT);
        assertThat(reload(reservation).getReleaseIdempotencyKey()).isEqualTo(key);
    }

    @Test
    void releaseOfExpiredSeatSucceedsAndRecordsFirstKey() {
        JobMatchingSeatReservation swept = reserve(saveJob(1));
        clock.advance(Duration.ofMinutes(10));
        service.expireOverdue(swept.getJobPostId());
        String key = newKey();

        assertThat(service.release(swept.getJobPostId(), swept.getId(), key).getStatus())
                .isEqualTo(MatchingSeatReservationStatus.EXPIRED);
        assertThat(service.release(swept.getJobPostId(), swept.getId(), key).getStatus())
                .isEqualTo(MatchingSeatReservationStatus.EXPIRED);
        assertError(() -> service.release(swept.getJobPostId(), swept.getId(), newKey()),
                JobErrorCode.SEAT_RESERVATION_STATE_CONFLICT);
    }

    @Test
    void releaseOfOverdueReservedSeatExpiresIt() {
        JobMatchingSeatReservation overdue = reserve(saveJob(1));
        clock.advance(Duration.ofMinutes(10));

        JobMatchingSeatReservation result = service.release(overdue.getJobPostId(), overdue.getId(), newKey());

        assertThat(result.getStatus()).isEqualTo(MatchingSeatReservationStatus.EXPIRED);
        assertThat(result.getReleasedAt()).isNull();
        assertThat(result.getExpiredAt()).isNotNull();
    }

    @Test
    void rejectsReleaseOfConsumedSeatAndReleaseKeyReuse() {
        JobPost jobPost = saveJob(2);
        JobMatchingSeatReservation consumed = reserve(jobPost);
        service.confirm(jobPost.getId(), consumed.getId(), newKey());
        JobMatchingSeatReservation first = reserve(jobPost);
        String key = newKey();
        service.release(jobPost.getId(), first.getId(), key);

        assertError(() -> service.release(jobPost.getId(), consumed.getId(), newKey()),
                JobErrorCode.SEAT_RESERVATION_STATE_CONFLICT);
        assertError(() -> service.release(jobPost.getId(), consumed.getId(), key),
                JobErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(reload(consumed).getStatus()).isEqualTo(MatchingSeatReservationStatus.CONSUMED);
    }

    @Test
    void lateReleaseOfOldAttemptDoesNotAffectReplacementReservation() {
        JobPost jobPost = saveJob(1);
        Long matchingId = nextId();
        Long applicationId = nextId();
        JobMatchingSeatReservation old = service.reserve(jobPost.getId(), matchingId, applicationId, 100L, newKey());
        String oldReleaseKey = newKey();
        service.release(jobPost.getId(), old.getId(), oldReleaseKey);

        JobMatchingSeatReservation replacement =
                service.reserve(jobPost.getId(), matchingId, applicationId, 100L, newKey());
        service.release(jobPost.getId(), old.getId(), oldReleaseKey);

        assertThat(reload(replacement).getStatus()).isEqualTo(MatchingSeatReservationStatus.RESERVED);
        assertThat(service.confirm(jobPost.getId(), replacement.getId(), newKey()).getStatus())
                .isEqualTo(MatchingSeatReservationStatus.CONSUMED);
    }

    @Test
    void anotherApplicantCanReserveAfterExpiredSeatIsSwept() {
        JobPost jobPost = saveJob(1);
        JobMatchingSeatReservation first = reserve(jobPost);
        assertError(() -> reserve(jobPost), JobErrorCode.RECRUITMENT_SEAT_UNAVAILABLE);

        clock.advance(Duration.ofMinutes(10));
        assertThat(service.expireOverdue(jobPost.getId())).isOne();
        assertThat(service.expireOverdue(jobPost.getId())).isZero();

        assertThat(reserve(jobPost).getStatus()).isEqualTo(MatchingSeatReservationStatus.RESERVED);
        assertThat(reload(first).getStatus()).isEqualTo(MatchingSeatReservationStatus.EXPIRED);
    }

    @RepeatedTest(3)
    void confirmAndReleaseRaceResolvesToOneOutcome() throws Exception {
        JobMatchingSeatReservation reservation = reserve(saveJob(1));

        List<Object> results = runConcurrently(
                () -> service.confirm(reservation.getJobPostId(), reservation.getId(), newKey()),
                () -> service.release(reservation.getJobPostId(), reservation.getId(), newKey())
        );

        MatchingSeatReservationStatus finalStatus = reload(reservation).getStatus();
        assertThat(results).filteredOn(JobMatchingSeatReservation.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(BusinessException.class::isInstance)
                .extracting(result -> ((BusinessException) result).getErrorCode())
                .containsExactly(JobErrorCode.SEAT_RESERVATION_STATE_CONFLICT);
        assertThat(finalStatus).isIn(MatchingSeatReservationStatus.CONSUMED, MatchingSeatReservationStatus.RELEASED);
    }

    @RepeatedTest(3)
    void confirmAndExpiryRaceAtBoundaryResolvesToExpiry() throws Exception {
        JobMatchingSeatReservation reservation = reserve(saveJob(1));
        clock.advance(Duration.ofMinutes(10));

        List<Object> results = runConcurrently(
                () -> service.confirm(reservation.getJobPostId(), reservation.getId(), newKey()),
                () -> service.expireOverdue(reservation.getJobPostId())
        );

        assertThat(results).filteredOn(BusinessException.class::isInstance)
                .extracting(result -> ((BusinessException) result).getErrorCode())
                .containsExactly(JobErrorCode.SEAT_RESERVATION_EXPIRED);
        assertThat(results).contains(1);
        assertThat(reload(reservation).getStatus()).isEqualTo(MatchingSeatReservationStatus.EXPIRED);
    }

    @RepeatedTest(3)
    void confirmAndExpiryRaceBeforeBoundaryResolvesToConsumption() throws Exception {
        JobMatchingSeatReservation reservation = reserve(saveJob(1));
        clock.advance(Duration.ofMinutes(10).minusNanos(1_000));

        List<Object> results = runConcurrently(
                () -> service.confirm(reservation.getJobPostId(), reservation.getId(), newKey()),
                () -> service.expireOverdue(reservation.getJobPostId())
        );

        assertThat(results).filteredOn(JobMatchingSeatReservation.class::isInstance).hasSize(1);
        assertThat(results).contains(0);
        assertThat(reload(reservation).getStatus()).isEqualTo(MatchingSeatReservationStatus.CONSUMED);
    }

    private List<Object> runConcurrently(Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : List.of(first, second)) {
            futures.add(executorService.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (BusinessException exception) {
                    return exception;
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private JobMatchingSeatReservation reserve(JobPost jobPost) {
        return service.reserve(jobPost.getId(), nextId(), nextId(), 100L, newKey());
    }

    private JobMatchingSeatReservation reload(JobMatchingSeatReservation reservation) {
        return reservationRepository.findById(reservation.getId()).orElseThrow();
    }

    private JobPost saveJob(int recruitCount) {
        return jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().recruitCount(recruitCount).build()));
    }

    private void assertError(ThrowingCallable callable, JobErrorCode errorCode) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }

    private Long nextId() {
        return SEQUENCE.incrementAndGet();
    }

    private String newKey() {
        return "seat-command-" + UUID.randomUUID();
    }
}
