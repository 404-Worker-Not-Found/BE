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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingSeatReservationConcurrencyTests extends IntegrationTestSupport {

    private static final int REQUESTS = 8;

    @Autowired
    private MatchingSeatReservationCommandService service;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobMatchingSeatReservationRepository reservationRepository;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(REQUESTS);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void concurrentReservationsNeverExceedRecruitCount() throws Exception {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost().recruitCount(3).build());

        List<Object> results = runConcurrently(index -> () ->
                service.reserve(jobPost.getId(), 50_000L + index, 60_000L + index, 100L, newKey()));

        assertThat(results).filteredOn(JobMatchingSeatReservation.class::isInstance).hasSize(3);
        assertThat(results).filteredOn(BusinessException.class::isInstance)
                .extracting(result -> ((BusinessException) result).getErrorCode())
                .hasSize(REQUESTS - 3)
                .containsOnly(JobErrorCode.RECRUITMENT_SEAT_UNAVAILABLE);
        assertThat(reservationRepository.countByJobPostIdAndStatusIn(jobPost.getId(),
                EnumSet.allOf(MatchingSeatReservationStatus.class))).isEqualTo(3);
    }

    @Test
    void concurrentFirstRequestsWithSameKeyCreateOneReservation() throws Exception {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost().recruitCount(3).build());
        String key = newKey();

        List<Object> results = runConcurrently(index -> () ->
                service.reserve(jobPost.getId(), 70_000L, 80_000L, 100L, key));

        assertThat(results).allMatch(JobMatchingSeatReservation.class::isInstance);
        assertThat(results).extracting(result -> ((JobMatchingSeatReservation) result).getId())
                .containsOnly(((JobMatchingSeatReservation) results.get(0)).getId());
        assertThat(reservationRepository.countByJobPostIdAndStatusIn(jobPost.getId(),
                EnumSet.allOf(MatchingSeatReservationStatus.class))).isOne();
    }

    // 만료 회수를 범위 UPDATE로 하면 빈 공고끼리 같은 gap을 잠가 INSERT가 교착된다. 회귀를 막는다.
    @Test
    void concurrentReservationsForDifferentJobsDoNotDeadlock() throws Exception {
        List<JobPost> jobPosts = new ArrayList<>();
        for (int index = 0; index < REQUESTS; index++) {
            jobPosts.add(jobPostRepository.save(JobPostFixture.jobPost().build()));
        }

        List<Object> results = runConcurrently(index -> () ->
                service.reserve(jobPosts.get(index).getId(), 90_000L + index, 95_000L + index, 100L, newKey()));

        assertThat(results).allMatch(JobMatchingSeatReservation.class::isInstance);
    }

    private List<Object> runConcurrently(java.util.function.IntFunction<Callable<Object>> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int index = 0; index < REQUESTS; index++) {
            Callable<Object> request = task.apply(index);
            futures.add(executorService.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return request.call();
                } catch (BusinessException exception) {
                    return exception;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private String newKey() {
        return "seat-concurrent-" + UUID.randomUUID();
    }
}
