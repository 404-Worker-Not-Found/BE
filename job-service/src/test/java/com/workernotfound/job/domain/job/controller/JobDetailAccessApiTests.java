package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.service.JobFindService;
import com.workernotfound.job.global.security.JwtProperties;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.TestAccessTokens;
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

// 공개 전 공고의 상세 조회는 인증된 점주 본인만 가능하고, 공개·마감 공고의 조회 정책은 그대로다.
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
    void internalLookupStillFindsPaymentPendingJob() {
        JobPost pending = savePendingJob();

        assertThat(jobFindService.findJobPost(pending.getId()).getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
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
