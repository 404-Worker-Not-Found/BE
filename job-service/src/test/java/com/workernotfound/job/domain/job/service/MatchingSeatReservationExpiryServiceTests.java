package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.MatchingSeatReservationStatus;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingSeatReservationExpiryServiceTests extends IntegrationTestSupport {

    @Autowired
    private MatchingSeatReservationExpiryService expiryService;

    @Autowired
    private MatchingSeatReservationCommandService commandService;

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
    void expiresOnlyOverdueReservedSeatsAcrossJobs() {
        clock.fixAtNow();
        JobMatchingSeatReservation first = reserve(saveJob());
        JobMatchingSeatReservation second = reserve(saveJob());
        JobMatchingSeatReservation consumed = reserve(saveJob());
        commandService.confirm(consumed.getJobPostId(), consumed.getId(), newKey());
        clock.advance(Duration.ofMinutes(5));
        JobMatchingSeatReservation fresh = reserve(saveJob());
        clock.advance(Duration.ofMinutes(5));

        sweepAll();

        LocalDateTime sweptAt = LocalDateTime.now(clock);
        assertExpiredAt(first, sweptAt);
        assertExpiredAt(second, sweptAt);
        assertThat(reload(consumed).getStatus()).isEqualTo(MatchingSeatReservationStatus.CONSUMED);
        assertThat(reload(fresh).getStatus()).isEqualTo(MatchingSeatReservationStatus.RESERVED);
    }

    @Test
    void repeatedAndConcurrentSweepsDoNotExpireTwice() throws Exception {
        clock.fixAtNow();
        JobMatchingSeatReservation reservation = reserve(saveJob());
        clock.advance(Duration.ofMinutes(10));
        LocalDateTime firstSweep = LocalDateTime.now(clock);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = executor.submit(() -> commandService.expireOverdue(reservation.getJobPostId()));
            Future<Integer> b = executor.submit(() -> commandService.expireOverdue(reservation.getJobPostId()));
            assertThat(a.get(30, TimeUnit.SECONDS) + b.get(30, TimeUnit.SECONDS)).isOne();
        } finally {
            executor.shutdownNow();
        }

        clock.advance(Duration.ofMinutes(1));
        sweepAll();
        assertExpiredAt(reservation, firstSweep);
    }

    // 같은 DB를 쓰는 다른 테스트의 만료 대상이 한 배치를 채울 수 있으므로 남은 대상이 없을 때까지 실행한다.
    private void sweepAll() {
        for (int run = 0; run < 100 && expiryService.expireOverdueReservations() > 0; run++) {
        }
    }

    private void assertExpiredAt(JobMatchingSeatReservation reservation, LocalDateTime expiredAt) {
        JobMatchingSeatReservation reloaded = reload(reservation);
        assertThat(reloaded.getStatus()).isEqualTo(MatchingSeatReservationStatus.EXPIRED);
        assertThat(reloaded.getExpiredAt()).isEqualTo(expiredAt);
    }

    private JobMatchingSeatReservation reload(JobMatchingSeatReservation reservation) {
        return reservationRepository.findById(reservation.getId()).orElseThrow();
    }

    private JobMatchingSeatReservation reserve(JobPost jobPost) {
        return commandService.reserve(jobPost.getId(), 1L, 1L, 1L, newKey());
    }

    private JobPost saveJob() {
        return jobPostRepository.save(JobPostFixture.jobPost().build());
    }

    private String newKey() {
        return "seat-expiry-" + UUID.randomUUID();
    }
}
