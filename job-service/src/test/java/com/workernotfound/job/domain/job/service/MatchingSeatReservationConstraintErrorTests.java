package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.GlobalExceptionHandler;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

// 공고가 서로 다르면 공고 행 잠금이 직렬화하지 않으므로 유일 제약이 마지막 방어선이다.
@AutoConfigureMockMvc
class MatchingSeatReservationConstraintErrorTests extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private MatchingSeatReservationCommandService service;

    @MockitoSpyBean
    private GlobalExceptionHandler globalExceptionHandler;

    @MockitoSpyBean
    private JobMatchingSeatReservationRepository reservationRepository;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(2);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(globalExceptionHandler).handleException(any(), any());
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void mapsConcurrentReservationKeyReuseAcrossJobsToConflict() throws Exception {
        JobPost first = saveJob();
        JobPost second = jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().ownerId(9001L).build()));
        String key = newKey();
        doAnswer(awaitBoth(new CountDownLatch(2))).when(reservationRepository).findByIdempotencyKey(key);

        List<MvcResult> results = runBoth(
                () -> perform("/api/jobs/internal/{jobPostId}/matching-seat-reservations", first.getId(), key,
                        "{\"matchingId\":1,\"applicationId\":1,\"workerMemberId\":1}"),
                () -> perform("/api/jobs/internal/{jobPostId}/matching-seat-reservations", second.getId(), key,
                        "{\"matchingId\":2,\"applicationId\":2,\"workerMemberId\":2}"));

        assertOneConflict(results);
    }

    @Test
    void mapsConcurrentConfirmKeyReuseAcrossJobsToConflict() throws Exception {
        JobMatchingSeatReservation first = reserve(saveJob());
        JobMatchingSeatReservation second = reserve(saveJob());
        String key = newKey();
        doAnswer(awaitBoth(new CountDownLatch(2))).when(reservationRepository).findByConfirmIdempotencyKey(key);

        List<MvcResult> results = runBoth(
                () -> performCommand(first, "confirm", key),
                () -> performCommand(second, "confirm", key));

        assertOneConflict(results);
    }

    @Test
    void mapsConcurrentReleaseKeyReuseAcrossJobsToConflict() throws Exception {
        JobMatchingSeatReservation first = reserve(saveJob());
        JobMatchingSeatReservation second = reserve(saveJob());
        String key = newKey();
        doAnswer(awaitBoth(new CountDownLatch(2))).when(reservationRepository).findByReleaseIdempotencyKey(key);

        List<MvcResult> results = runBoth(
                () -> performCommand(first, "release", key),
                () -> performCommand(second, "release", key));

        assertOneConflict(results);
    }

    // 두 요청이 모두 키 조회를 지난 뒤 저장하도록 맞춘다.
    private Answer<Optional<JobMatchingSeatReservation>> awaitBoth(CountDownLatch lookups) {
        return invocation -> {
            lookups.countDown();
            assertThat(lookups.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.empty();
        };
    }

    private void assertOneConflict(List<MvcResult> results) throws Exception {
        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 409);
        MvcResult conflict = results.stream()
                .filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
        jsonPath("$.code").value("JOB-409-004").match(conflict);
        assertThat(conflict.getResolvedException()).isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<MvcResult> runBoth(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        List<Future<MvcResult>> futures = List.of(executorService.submit(first), executorService.submit(second));
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private MvcResult performCommand(JobMatchingSeatReservation reservation, String command, String key)
            throws Exception {
        return mockMvc.perform(post("/api/jobs/internal/{jobPostId}/matching-seat-reservations/{reservationId}/"
                                + command, reservation.getJobPostId(), reservation.getId())
                        .header("X-Internal-Secret", "test-internal-secret")
                        .header("Idempotency-Key", key))
                .andReturn();
    }

    private MvcResult perform(String path, Long jobPostId, String key, String body) throws Exception {
        return mockMvc.perform(post(path, jobPostId)
                        .header("X-Internal-Secret", "test-internal-secret")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JobMatchingSeatReservation reserve(JobPost jobPost) {
        return service.reserve(jobPost.getId(), 1L, 1L, 1L, newKey());
    }

    private JobPost saveJob() {
        return jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().build()));
    }

    private String newKey() {
        return "seat-constraint-" + UUID.randomUUID();
    }
}
