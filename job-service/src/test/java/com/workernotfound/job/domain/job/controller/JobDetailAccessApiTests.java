package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.entity.FundingStatusNotification;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.FundingStatusResult;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.service.JobCloseCommandService;
import com.workernotfound.job.domain.job.service.JobFindService;
import com.workernotfound.job.domain.job.service.JobFundingStatusCommandService;
import com.workernotfound.job.domain.job.service.JobScheduleTransitionService;
import com.workernotfound.job.global.security.JwtProperties;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import com.workernotfound.job.support.TestAccessTokens;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 공개된 적 없는 공고(결제 대기, 공개 전 마감)의 상세 조회는 인증된 점주 본인만 가능하고, 공개된 공고는 마감 뒤에도 공개 조회다.
@AutoConfigureMockMvc
class JobDetailAccessApiTests extends IntegrationTestSupport {

    // JobPostFixture의 점주 회원 ID
    private static final long OWNER_ID = 7L;
    private static final long OTHER_MEMBER_ID = 8L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobFindService jobFindService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JobScheduleTransitionService transitionService;

    @Autowired
    private JobCloseCommandService closeService;

    @Autowired
    private JobFundingStatusCommandService fundingService;

    @Autowired
    private MutableClock clock;

    @Test
    void ownerCanReadOwnPaymentPendingJob() throws Exception {
        JobPost pending = savePendingJob();

        detail(pending, TestAccessTokens.owner(jwtProperties.secret(), OWNER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(pending.getId()));
    }

    @Test
    void otherMembersAndAnonymousRequestsGetNotFoundForPaymentPendingJob() throws Exception {
        JobPost pending = savePendingJob();

        assertNotFound(detail(pending, TestAccessTokens.owner(jwtProperties.secret(), OTHER_MEMBER_ID)));
        assertNotFound(detail(pending, TestAccessTokens.worker(jwtProperties.secret(), OTHER_MEMBER_ID)));
        assertNotFound(mockMvc.perform(get("/api/jobs/{id}", pending.getId())));
        // 위조된 토큰은 익명 요청으로 처리되므로 점주 회원 ID를 담아도 조회할 수 없다.
        assertNotFound(mockMvc.perform(get("/api/jobs/{id}", pending.getId())
                .header("Authorization", "Bearer forged.token.value")));
    }

    @Test
    void openAndClosedJobsRemainPublic() throws Exception {
        JobPost open = jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().build()));
        JobPost closed = jobPostRepository.save(
                JobPostFixture.withStatus(JobPostFixture.jobPost().build(), JobStatus.CLOSED));

        mockMvc.perform(get("/api/jobs/{id}", open.getId())).andExpect(status().isOk());
        mockMvc.perform(get("/api/jobs/{id}", closed.getId())).andExpect(status().isOk());
        detail(open, TestAccessTokens.owner(jwtProperties.secret(), OTHER_MEMBER_ID)).andExpect(status().isOk());
    }

    @Test
    void jobClosedBeforePublicationByDeadlineStaysVisibleOnlyToOwner() throws Exception {
        JobPost pending = savePendingJob();
        LocalDateTime deadline = pending.getApplicationDeadline();
        clock.fixAt(deadline.atZone(clock.getZone()).toInstant());
        try {
            assertThat(transitionService.applyIfDue(pending.getId())).isTrue();
        } finally {
            clock.reset();
        }

        assertThat(reload(pending).getStatus()).isEqualTo(JobStatus.CLOSED);
        assertVisibleOnlyToOwner(pending);
    }

    @Test
    void jobClosedBeforePublicationByOwnerStaysVisibleOnlyToOwner() throws Exception {
        JobPost pending = savePendingJob();
        closeService.close(pending.getId(), OWNER_ID, UUID.randomUUID().toString());

        assertThat(reload(pending).getStatus()).isEqualTo(JobStatus.CLOSED);
        assertVisibleOnlyToOwner(pending);
    }

    @Test
    void jobClosedAfterPublicationRemainsPublic() throws Exception {
        JobPost unsaved = JobPostFixture.jobPost().build();
        unsaved.linkPaymentOrder("order-" + UUID.randomUUID(), 1L, 90_000L, "KRW");
        JobPost pending = jobPostRepository.save(unsaved);
        FundingStatusNotification funded = new FundingStatusNotification(
                pending.getPaymentOrderId(), 1L, OWNER_ID, 90_000L, "KRW", 1L, true);
        assertThat(fundingService.receive(pending.getId(), funded, UUID.randomUUID().toString()).getResult())
                .isEqualTo(FundingStatusResult.PUBLISHED);
        closeService.close(pending.getId(), OWNER_ID, UUID.randomUUID().toString());

        assertThat(reload(pending).getStatus()).isEqualTo(JobStatus.CLOSED);
        mockMvc.perform(get("/api/jobs/{id}", pending.getId())).andExpect(status().isOk());
        detail(pending, TestAccessTokens.owner(jwtProperties.secret(), OTHER_MEMBER_ID)).andExpect(status().isOk());
        detail(pending, TestAccessTokens.worker(jwtProperties.secret(), OTHER_MEMBER_ID)).andExpect(status().isOk());
    }

    @Test
    void internalLookupStillFindsPaymentPendingJob() {
        JobPost pending = savePendingJob();

        assertThat(jobFindService.findJobPost(pending.getId()).getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
    }

    private void assertVisibleOnlyToOwner(JobPost jobPost) throws Exception {
        detail(jobPost, TestAccessTokens.owner(jwtProperties.secret(), OWNER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(jobPost.getId()));
        assertNotFound(detail(jobPost, TestAccessTokens.owner(jwtProperties.secret(), OTHER_MEMBER_ID)));
        assertNotFound(detail(jobPost, TestAccessTokens.worker(jwtProperties.secret(), OTHER_MEMBER_ID)));
        assertNotFound(mockMvc.perform(get("/api/jobs/{id}", jobPost.getId())));
    }

    private JobPost reload(JobPost jobPost) {
        return jobPostRepository.findById(jobPost.getId()).orElseThrow();
    }

    private JobPost savePendingJob() {
        JobPost pending = jobPostRepository.save(JobPostFixture.jobPost().build());
        assertThat(pending.getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        return pending;
    }

    private ResultActions detail(JobPost jobPost, String token) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/jobs/{id}", jobPost.getId())
                .header("Authorization", TestAccessTokens.bearer(token));
        return mockMvc.perform(request);
    }

    private void assertNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB-404-001"));
    }
}
