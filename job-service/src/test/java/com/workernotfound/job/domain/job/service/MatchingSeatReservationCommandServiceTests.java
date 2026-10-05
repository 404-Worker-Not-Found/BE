package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingSeatReservationCommandServiceTests extends IntegrationTestSupport {

    private static final AtomicLong SEQUENCE = new AtomicLong(1_000);

    @Autowired
    private MatchingSeatReservationCommandService service;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobMatchingSeatReservationRepository reservationRepository;

    @Autowired
    private MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void reservesSeatWithIssuedJobSnapshot() {
        JobPost jobPost = save(JobPostFixture.jobPost()
                .startTime(LocalTime.of(22, 0))
                .endTime(LocalTime.of(2, 30))
                .endTimeNextDay(true)
                .extraWage(2_000));
        clock.fixAtNow();

        JobMatchingSeatReservation reservation = reserve(jobPost, nextId(), nextId(), newKey());

        assertThat(reservation.getStatus()).isEqualTo(MatchingSeatReservationStatus.RESERVED);
        assertThat(reservation.getJobVersion()).isEqualTo(jobPost.getVersion());
        assertThat(reservation.getOwnerMemberId()).isEqualTo(7L);
        assertThat(reservation.getWorkDate()).isEqualTo(jobPost.getWorkDate());
        assertThat(reservation.getStartTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(reservation.getEndTime()).isEqualTo(LocalTime.of(2, 30));
        assertThat(reservation.isEndTimeNextDay()).isTrue();
        // 4시간 30분 × (10,000 + 2,000)원
        assertThat(reservation.getLockedAmount()).isEqualTo(54_000L);
        assertThat(reservation.getCurrency()).isEqualTo("KRW");
        assertThat(reservation.getReservedAt()).isEqualTo(JobMatchingSeatReservation.toStoredTime(LocalDateTime.now(clock)));
        assertThat(reservation.getExpiresAt()).isEqualTo(reservation.getReservedAt().plusMinutes(10));
    }

    @Test
    void returnsSameReservationForSameKeyWithoutReadingChangedJob() {
        JobPost jobPost = save(JobPostFixture.jobPost());
        Long matchingId = nextId();
        Long applicationId = nextId();
        String key = newKey();
        JobMatchingSeatReservation first = reserve(jobPost, matchingId, applicationId, key);

        changeJobPost(jobPost.getId());
        JobMatchingSeatReservation second = reserve(jobPost, matchingId, applicationId, key);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getJobVersion()).isEqualTo(first.getJobVersion());
        assertThat(second.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(second.getLockedAmount()).isEqualTo(90_000L);
        assertThat(second.getExpiresAt()).isEqualTo(first.getExpiresAt());
    }

    @Test
    void rejectsSameKeyForDifferentRequest() {
        JobPost jobPost = save(JobPostFixture.jobPost().recruitCount(5));
        JobPost otherJobPost = save(JobPostFixture.jobPost());
        Long matchingId = nextId();
        Long applicationId = nextId();
        String key = newKey();
        reserve(jobPost, matchingId, applicationId, key);

        assertError(() -> service.reserve(otherJobPost.getId(), matchingId, applicationId, 100L, key),
                JobErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertError(() -> service.reserve(jobPost.getId(), nextId(), applicationId, 100L, key),
                JobErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertError(() -> service.reserve(jobPost.getId(), matchingId, nextId(), 100L, key),
                JobErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertError(() -> service.reserve(jobPost.getId(), matchingId, applicationId, 101L, key),
                JobErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void preventsSameMatchingOrApplicationFromHoldingTwoSeatsWithDifferentKeys() {
        JobPost jobPost = save(JobPostFixture.jobPost().recruitCount(5));
        Long matchingId = nextId();
        Long applicationId = nextId();
        reserve(jobPost, matchingId, applicationId, newKey());

        assertError(() -> reserve(jobPost, matchingId, nextId(), newKey()), JobErrorCode.SEAT_ALREADY_HELD);
        assertError(() -> reserve(jobPost, nextId(), applicationId, newKey()), JobErrorCode.SEAT_ALREADY_HELD);
    }

    @Test
    void rejectsWhenRecruitCountIsFilled() {
        JobPost jobPost = save(JobPostFixture.jobPost().recruitCount(2));
        reserve(jobPost, nextId(), nextId(), newKey());
        reserve(jobPost, nextId(), nextId(), newKey());

        assertError(() -> reserve(jobPost, nextId(), nextId(), newKey()), JobErrorCode.RECRUITMENT_SEAT_UNAVAILABLE);
    }

    @Test
    void allowsOpenAndMatchingButRejectsClosedJob() {
        JobPost matching = save(JobPostFixture.withStatus(JobPostFixture.jobPost().build(), JobStatus.MATCHING));
        JobPost closed = save(JobPostFixture.withStatus(JobPostFixture.jobPost().build(), JobStatus.CLOSED));

        assertThat(reserve(matching, nextId(), nextId(), newKey()).getId()).isNotNull();
        assertError(() -> reserve(closed, nextId(), nextId(), newKey()), JobErrorCode.JOB_NOT_MATCHABLE);
    }

    @Test
    void usesWorkStartInsteadOfApplicationDeadline() {
        LocalDateTime start = LocalDateTime.now().plusHours(2).withSecond(0).withNano(0);
        JobPost jobPost = save(JobPostFixture.jobPost()
                .workDate(start.toLocalDate())
                .startTime(start.toLocalTime())
                .endTime(start.toLocalTime().plusMinutes(30))
                .endTimeNextDay(start.toLocalTime().plusMinutes(30).isBefore(start.toLocalTime()))
                .recruitCount(2)
                .applicationDeadline(LocalDateTime.now().minusMinutes(1)));

        assertThat(reserve(jobPost, nextId(), nextId(), newKey()).getId()).isNotNull();

        clock.fixAt(start.atZone(clock.getZone()).toInstant());
        assertError(() -> reserve(jobPost, nextId(), nextId(), newKey()), JobErrorCode.WORK_ALREADY_STARTED);
    }

    @Test
    void expiredReservationDoesNotBlockNewReservationEvenWithoutScheduler() {
        JobPost jobPost = save(JobPostFixture.jobPost());
        clock.fixAtNow();
        JobMatchingSeatReservation expired = reserve(jobPost, nextId(), nextId(), newKey());

        clock.advance(Duration.ofMinutes(10));
        JobMatchingSeatReservation next = reserve(jobPost, nextId(), nextId(), newKey());

        assertThat(next.getStatus()).isEqualTo(MatchingSeatReservationStatus.RESERVED);
        JobMatchingSeatReservation reloaded = reservationRepository.findById(expired.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MatchingSeatReservationStatus.EXPIRED);
        assertThat(reloaded.getExpiredAt()).isEqualTo(JobMatchingSeatReservation.toStoredTime(LocalDateTime.now(clock)));
    }

    @Test
    void sameKeyReturnsOriginalSnapshotForExpiredReservationWithoutReviving() {
        JobPost jobPost = save(JobPostFixture.jobPost());
        Long matchingId = nextId();
        Long applicationId = nextId();
        String key = newKey();
        clock.fixAtNow();
        JobMatchingSeatReservation first = reserve(jobPost, matchingId, applicationId, key);
        clock.advance(Duration.ofMinutes(11));
        service.expireOverdue(jobPost.getId());

        JobMatchingSeatReservation replay = reserve(jobPost, matchingId, applicationId, key);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getStatus()).isEqualTo(MatchingSeatReservationStatus.EXPIRED);
        assertThat(replay.getExpiresAt()).isEqualTo(first.getExpiresAt());
        assertThat(reservationRepository.countByJobPostIdAndStatusIn(
                jobPost.getId(), java.util.List.of(MatchingSeatReservationStatus.RESERVED))).isZero();
    }

    @Test
    void sameMatchingCanReserveWithNewKeyAfterPreviousAttemptExpired() {
        JobPost jobPost = save(JobPostFixture.jobPost());
        Long matchingId = nextId();
        Long applicationId = nextId();
        clock.fixAtNow();
        JobMatchingSeatReservation first = reserve(jobPost, matchingId, applicationId, newKey());
        clock.advance(Duration.ofMinutes(10));

        JobMatchingSeatReservation retry = reserve(jobPost, matchingId, applicationId, newKey());

        assertThat(retry.getId()).isNotEqualTo(first.getId());
        assertThat(retry.getStatus()).isEqualTo(MatchingSeatReservationStatus.RESERVED);
    }

    @Test
    void rejectsJobWhoseWageCannotBeCalculated() {
        // 등록 검증 이전에 저장된 공고처럼 24시간을 넘는 익일 근무는 금액을 계산하지 않는다.
        JobPost jobPost = save(JobPostFixture.jobPost()
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(10, 0))
                .endTimeNextDay(true));

        assertError(() -> reserve(jobPost, nextId(), nextId(), newKey()), JobErrorCode.INVALID_WORK_TIME);
        assertThat(reservationRepository.countByJobPostIdAndStatusIn(jobPost.getId(),
                java.util.EnumSet.allOf(MatchingSeatReservationStatus.class))).isZero();
    }

    @Test
    void throwsWhenJobNotFound() {
        assertError(() -> service.reserve(999_999L, nextId(), nextId(), 100L, newKey()), JobErrorCode.JOB_NOT_FOUND);
    }

    private JobMatchingSeatReservation reserve(JobPost jobPost, Long matchingId, Long applicationId, String key) {
        return service.reserve(jobPost.getId(), matchingId, applicationId, 100L, key);
    }

    private void changeJobPost(Long jobPostId) {
        JobPost jobPost = jobPostRepository.findById(jobPostId).orElseThrow();
        ReflectionTestUtils.setField(jobPost, "startTime", LocalTime.of(10, 0));
        ReflectionTestUtils.setField(jobPost, "baseHourlyWage", 20_000);
        ReflectionTestUtils.setField(jobPost, "workDate", LocalDate.now().plusDays(3));
        jobPostRepository.saveAndFlush(jobPost);
    }

    private JobPost save(JobPost.JobPostBuilder builder) {
        return jobPostRepository.save(JobPostFixture.open(builder.build()));
    }

    private JobPost save(JobPost jobPost) {
        return jobPostRepository.save(jobPost);
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
        return "seat-" + UUID.randomUUID();
    }
}
