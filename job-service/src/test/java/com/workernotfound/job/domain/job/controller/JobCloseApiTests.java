package com.workernotfound.job.domain.job.controller;

import com.jayway.jsonpath.JsonPath;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.JobStatusHistory;
import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobCloseRequestRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.JobStatusHistoryRepository;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import com.workernotfound.job.global.security.JwtProperties;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.TestAccessTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 점주 수동 마감 API. 권한, 상태별 전이와 모집 종료 알림, 요청 멱등성과 키 재사용, 이미 마감된 공고의 처리를 검증한다.
 */
@AutoConfigureMockMvc
class JobCloseApiTests extends IntegrationTestSupport {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong(1_200_000);
    private static final String MANUAL_CLOSE_REASON = "OWNER:MANUAL_CLOSE";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobStatusHistoryRepository historyRepository;

    @Autowired
    private RecruitmentCompletionCommandRepository commandRepository;

    @Autowired
    private JobCloseRequestRepository closeRequestRepository;

    @Autowired
    private JwtProperties jwtProperties;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void onlyAuthenticatedOwnerOfJobCanClose() throws Exception {
        JobPost jobPost = saveJob(JobStatus.OPEN);

        mockMvc.perform(post("/api/jobs/{id}/close", jobPost.getId()).header("Idempotency-Key", newKey()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
        mockMvc.perform(post("/api/jobs/{id}/close", jobPost.getId())
                        .header("Authorization", TestAccessTokens.bearer(
                                TestAccessTokens.worker(jwtProperties.secret(), jobPost.getOwnerId())))
                        .header("Idempotency-Key", newKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GLOBAL-403-001"));
        // 다른 점주에게는 공고의 존재를 드러내지 않는다.
        close(jobPost.getId(), jobPost.getOwnerId() + 1, newKey())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB-404-001"));
        close(Long.MAX_VALUE, jobPost.getOwnerId(), newKey())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB-404-001"));

        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId())).isEmpty();
    }

    @Test
    void rejectsMissingOrMalformedIdempotencyKey() throws Exception {
        JobPost jobPost = saveJob(JobStatus.OPEN);
        String owner = ownerToken(jobPost.getOwnerId());

        mockMvc.perform(post("/api/jobs/{id}/close", jobPost.getId()).header("Authorization", owner))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/jobs/{id}/close", jobPost.getId())
                        .header("Authorization", owner)
                        .header("Idempotency-Key", "has space"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/jobs/{id}/close", jobPost.getId())
                        .header("Authorization", owner)
                        .header("Idempotency-Key", "k".repeat(101)))
                .andExpect(status().isBadRequest());

        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.OPEN);
    }

    @Test
    void closingRecruitingJobNotifiesMatchingWithClosedVersion() throws Exception {
        for (JobStatus status : List.of(JobStatus.OPEN, JobStatus.MATCHING)) {
            JobPost jobPost = saveJob(status);

            close(jobPost.getId(), jobPost.getOwnerId(), newKey())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.jobPostId").value(jobPost.getId()))
                    .andExpect(jsonPath("$.data.result").value("CLOSED"))
                    .andExpect(jsonPath("$.data.previousStatus").value(status.name()))
                    .andExpect(jsonPath("$.data.status").value("CLOSED"));

            JobPost closed = reload(jobPost);
            assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
            assertSingleHistory(jobPost, status);
            List<RecruitmentCompletionCommand> commands = commandRepository.findByJobPostId(jobPost.getId());
            assertThat(commands).hasSize(1);
            assertThat(commands.get(0).getJobVersion()).isEqualTo(closed.getVersion());
        }
    }

    @Test
    void closingUnpublishedJobDoesNotNotifyMatching() throws Exception {
        JobPost jobPost = saveJob(JobStatus.PAYMENT_PENDING);

        close(jobPost.getId(), jobPost.getOwnerId(), newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("CLOSED"))
                .andExpect(jsonPath("$.data.previousStatus").value("PAYMENT_PENDING"));

        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.CLOSED);
        assertSingleHistory(jobPost, JobStatus.PAYMENT_PENDING);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).isEmpty();
    }

    @Test
    void sameKeyReturnsFirstResultWithoutDuplicates() throws Exception {
        JobPost jobPost = saveJob(JobStatus.OPEN);
        String key = newKey();

        String first = close(jobPost.getId(), jobPost.getOwnerId(), key)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String replay = close(jobPost.getId(), jobPost.getOwnerId(), key)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // 응답 envelope의 timestamp는 요청마다 다르다. 처리 결과(data)는 처음과 같아야 한다.
        Map<String, Object> firstData = JsonPath.read(first, "$.data");
        Map<String, Object> replayData = JsonPath.read(replay, "$.data");
        assertThat(replayData).isEqualTo(firstData);
        assertThat(firstData).containsEntry("result", "CLOSED").containsEntry("previousStatus", "OPEN");
        assertSingleHistory(jobPost, JobStatus.OPEN);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).hasSize(1);
    }

    @Test
    void alreadyClosedJobSucceedsWithoutChange() throws Exception {
        JobPost jobPost = saveJob(JobStatus.OPEN);
        close(jobPost.getId(), jobPost.getOwnerId(), newKey()).andExpect(status().isOk());
        Long closedVersion = reload(jobPost).getVersion();

        close(jobPost.getId(), jobPost.getOwnerId(), newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("ALREADY_CLOSED"))
                .andExpect(jsonPath("$.data.previousStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        assertThat(reload(jobPost).getVersion()).isEqualTo(closedVersion);
        assertSingleHistory(jobPost, JobStatus.OPEN);
        assertThat(commandRepository.findByJobPostId(jobPost.getId())).hasSize(1);
    }

    @Test
    void reusingKeyForAnotherJobOrOwnerIsConflict() throws Exception {
        JobPost first = saveJob(JobStatus.OPEN);
        JobPost sameOwnerOther = saveJob(JobStatus.OPEN, first.getOwnerId());
        JobPost otherOwner = saveJob(JobStatus.OPEN);
        String key = newKey();
        close(first.getId(), first.getOwnerId(), key).andExpect(status().isOk());

        close(sameOwnerOther.getId(), sameOwnerOther.getOwnerId(), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-004"));
        close(otherOwner.getId(), otherOwner.getOwnerId(), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-004"));

        for (JobPost untouched : List.of(sameOwnerOther, otherOwner)) {
            assertThat(reload(untouched).getStatus()).isEqualTo(JobStatus.OPEN);
            assertThat(historyRepository.findByJobPostIdOrderByIdAsc(untouched.getId())).isEmpty();
            assertThat(commandRepository.findByJobPostId(untouched.getId())).isEmpty();
        }
    }

    @Test
    void concurrentSameKeyOnDifferentJobsClosesOnlyOne() throws Exception {
        JobPost first = saveJob(JobStatus.OPEN);
        JobPost second = saveJob(JobStatus.OPEN, first.getOwnerId());
        String key = newKey();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> results = new ArrayList<>();
        for (JobPost jobPost : List.of(first, second)) {
            results.add(executor.submit(() -> {
                start.await();
                return close(jobPost.getId(), jobPost.getOwnerId(), key).andReturn();
            }));
        }
        start.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<MvcResult> result : results) {
            MvcResult response = result.get(30, TimeUnit.SECONDS);
            statuses.add(response.getResponse().getStatus());
            if (response.getResponse().getStatus() == 409) {
                assertThat(response.getResponse().getContentAsString()).contains("JOB-409-004");
            }
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        long closedJobs = List.of(first, second).stream()
                .filter(jobPost -> reload(jobPost).getStatus() == JobStatus.CLOSED)
                .count();
        assertThat(closedJobs).isEqualTo(1);
        assertThat(closeRequestRepository.findByIdempotencyKey(key)).isPresent();
    }

    private void assertSingleHistory(JobPost jobPost, JobStatus fromStatus) {
        List<JobStatusHistory> histories = historyRepository.findByJobPostIdOrderByIdAsc(jobPost.getId());
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getFromStatus()).isEqualTo(fromStatus);
        assertThat(histories.get(0).getToStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(histories.get(0).getReason()).isEqualTo(MANUAL_CLOSE_REASON);
    }

    private ResultActions close(Long jobPostId, Long ownerMemberId, String key) throws Exception {
        return mockMvc.perform(post("/api/jobs/{id}/close", jobPostId)
                .header("Authorization", ownerToken(ownerMemberId))
                .header("Idempotency-Key", key));
    }

    private String ownerToken(Long ownerMemberId) {
        return TestAccessTokens.bearer(TestAccessTokens.owner(jwtProperties.secret(), ownerMemberId));
    }

    private JobPost reload(JobPost jobPost) {
        return jobPostRepository.findById(jobPost.getId()).orElseThrow();
    }

    private JobPost saveJob(JobStatus status) {
        return saveJob(status, OWNER_SEQUENCE.incrementAndGet());
    }

    private JobPost saveJob(JobStatus status, Long ownerId) {
        return jobPostRepository.save(JobPostFixture.withStatus(
                JobPostFixture.jobPost().ownerId(ownerId).build(), status));
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
