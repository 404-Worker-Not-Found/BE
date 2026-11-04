package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;
import com.workernotfound.job.domain.job.event.ApplicationStatusChangedEvent;
import com.workernotfound.job.domain.job.port.ApplicationStateSnapshot;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.service.ApplicantCountProjectionService;
import com.workernotfound.job.global.security.JwtProperties;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.TestAccessTokens;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 공고 상세와 점주 본인 공고 목록은 초기 적재가 끝난 공고에만 현재 APPLIED 지원 수를 applicantCount로 보여 준다.
@AutoConfigureMockMvc
class ApplicantCountApiTests extends IntegrationTestSupport {

    private static final AtomicLong OWNER_IDS = new AtomicLong(9_163_000);
    private static final AtomicLong APPLICATION_IDS = new AtomicLong(9_163_000);
    private static final LocalDateTime FETCHED_AT = LocalDateTime.of(2026, 11, 3, 10, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private ApplicantCountProjectionService projectionService;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void detailShowsNullBeforeInitialSyncThenCurrentAppliedCount() throws Exception {
        JobPost post = saveOpenJob(OWNER_IDS.incrementAndGet());
        long canceledLater = APPLICATION_IDS.incrementAndGet();
        apply(post, canceledLater, ApplicationStatus.APPLIED, 1);

        mockMvc.perform(get("/api/jobs/{id}", post.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicantCount").value(Matchers.nullValue()));

        projectionService.applySnapshot(post.getId(), List.of(
                new ApplicationStateSnapshot(canceledLater, ApplicationStatus.APPLIED, 1L),
                new ApplicationStateSnapshot(APPLICATION_IDS.incrementAndGet(), ApplicationStatus.APPLIED, 1L),
                new ApplicationStateSnapshot(APPLICATION_IDS.incrementAndGet(), ApplicationStatus.SELECTED, 2L)),
                FETCHED_AT);
        apply(post, canceledLater, ApplicationStatus.CANCELED, 2);

        mockMvc.perform(get("/api/jobs/{id}", post.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicantCount").value(1));
    }

    @Test
    void ownerListShowsCountForSyncedJobsAndNullForOthers() throws Exception {
        long ownerId = OWNER_IDS.incrementAndGet();
        JobPost unsynced = saveOpenJob(ownerId);
        JobPost withoutApplicants = saveOpenJob(ownerId);
        JobPost withApplicants = saveOpenJob(ownerId);
        projectionService.applySnapshot(withoutApplicants.getId(), List.of(), FETCHED_AT);
        projectionService.applySnapshot(withApplicants.getId(), List.of(
                new ApplicationStateSnapshot(APPLICATION_IDS.incrementAndGet(), ApplicationStatus.APPLIED, 1L),
                new ApplicationStateSnapshot(APPLICATION_IDS.incrementAndGet(), ApplicationStatus.APPLIED, 1L)),
                FETCHED_AT);

        mockMvc.perform(get("/api/jobs/me").header("Authorization", TestAccessTokens.bearer(
                        TestAccessTokens.owner(jwtProperties.secret(), ownerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath(cardPath(unsynced) + ".applicantCount").value(Matchers.contains(Matchers.nullValue())))
                .andExpect(jsonPath(cardPath(withoutApplicants) + ".applicantCount").value(Matchers.contains(0)))
                .andExpect(jsonPath(cardPath(withApplicants) + ".applicantCount").value(Matchers.contains(2)));
    }

    private JobPost saveOpenJob(long ownerId) {
        return jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().ownerId(ownerId).build()));
    }

    private void apply(JobPost post, long applicationId, ApplicationStatus status, long revision) {
        projectionService.apply(new ApplicationStatusChangedEvent(UUID.randomUUID().toString(), applicationId,
                post.getId(), status, revision));
    }

    private static String cardPath(JobPost post) {
        return "$.data.jobs[?(@.id == " + post.getId() + ")]";
    }
}
