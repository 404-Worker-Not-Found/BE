package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.security.JwtProperties;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.TestAccessTokens;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class JobDeleteApiTests extends IntegrationTestSupport {
    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong(9_750_000);
    @Autowired private MockMvc mockMvc;
    @Autowired private JobPostRepository repository;
    @Autowired private JwtProperties jwtProperties;

    @Test
    void requiresOwnerRoleAndOwnershipEvenAfterDeletion() throws Exception {
        JobPost post = save(JobStatus.CLOSED);
        mockMvc.perform(delete("/api/jobs/{id}", post.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/jobs/{id}", post.getId()).header("Authorization",
                        TestAccessTokens.bearer(TestAccessTokens.worker(jwtProperties.secret(), post.getOwnerId()))))
                .andExpect(status().isForbidden());
        remove(post.getId(), post.getOwnerId() + 1).andExpect(status().isNotFound());
        remove(Long.MAX_VALUE, post.getOwnerId()).andExpect(status().isNotFound());
        assertThat(reload(post).getDeletedAt()).isNull();
        remove(post.getId(), post.getOwnerId()).andExpect(status().isOk());
        remove(post.getId(), post.getOwnerId() + 1).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"PAYMENT_PENDING", "OPEN", "MATCHING"})
    void rejectsJobsThatAreNotClosed(JobStatus jobStatus) throws Exception {
        JobPost post = save(jobStatus);
        remove(post.getId(), post.getOwnerId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-016"));
        assertThat(reload(post).getDeletedAt()).isNull();
        assertThat(reload(post).getStatus()).isEqualTo(jobStatus);
    }

    @Test
    void repeatedDeletionPreservesTimestampVersionAndDetails() throws Exception {
        JobPost post = save(JobStatus.CLOSED);
        post.linkPaymentOrder("retained-order", 1L, 90_000L, "KRW");
        repository.saveAndFlush(post);
        remove(post.getId(), post.getOwnerId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        JobPost first = reload(post);
        assertThat(first.getDeletedAt()).isNotNull();
        remove(post.getId(), post.getOwnerId()).andExpect(status().isOk());
        JobPost replay = reload(post);
        assertThat(replay.getDeletedAt()).isEqualTo(first.getDeletedAt());
        assertThat(replay.getVersion()).isEqualTo(first.getVersion());
        assertThat(replay.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(replay.getPaymentOrderId()).isEqualTo("retained-order");
        mockMvc.perform(get("/api/jobs/{id}", post.getId())).andExpect(status().isOk());
        mockMvc.perform(get("/api/jobs/{id}/payment-order", post.getId())
                        .header("Authorization", ownerToken(post.getOwnerId())))
                .andExpect(status().isOk());
    }

    @Test
    void excludesDeletedJobsFromPagesStatusFiltersAndLargeOffsetTotals() throws Exception {
        JobPost hidden = save(JobStatus.CLOSED);
        Long owner = hidden.getOwnerId();
        JobPost visible = repository.save(JobPostFixture.withStatus(
                JobPostFixture.jobPost().ownerId(owner).build(), JobStatus.CLOSED));
        remove(hidden.getId(), owner).andExpect(status().isOk());
        for (String filter : new String[]{"", "CLOSED"}) {
            mockMvc.perform(get("/api/jobs/me").header("Authorization", ownerToken(owner))
                            .param("status", filter).param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalCount").value(1))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.jobs[0].id").value(visible.getId()));
            mockMvc.perform(get("/api/jobs/me").header("Authorization", ownerToken(owner))
                            .param("status", filter).param("page", "2147483647").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalCount").value(1))
                    .andExpect(jsonPath("$.data.jobs").isEmpty());
        }
        assertThat(repository.findByOwnerIdAndOptionalStatusWithOffset(owner, null, 20, 0L))
                .extracting(JobPost::getId).containsExactly(visible.getId());
        assertThat(repository.findByOwnerIdAndOptionalStatusWithOffset(owner, "CLOSED", 20, 0L))
                .extracting(JobPost::getId).containsExactly(visible.getId());
    }

    @Test
    void deletionKeepsUnpublishedDetailsPrivate() throws Exception {
        JobPost post = JobPostFixture.jobPost().ownerId(OWNER_SEQUENCE.addAndGet(2)).build();
        post.closeByOwner();
        post = repository.saveAndFlush(post);
        remove(post.getId(), post.getOwnerId()).andExpect(status().isOk());
        mockMvc.perform(get("/api/jobs/{id}", post.getId())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jobs/{id}", post.getId()).header("Authorization", ownerToken(post.getOwnerId())))
                .andExpect(status().isOk());
    }

    private JobPost save(JobStatus status) {
        return repository.saveAndFlush(JobPostFixture.withStatus(
                JobPostFixture.jobPost().ownerId(OWNER_SEQUENCE.addAndGet(2)).build(), status));
    }
    private JobPost reload(JobPost post) {
        return repository.findById(post.getId()).orElseThrow();
    }
    private ResultActions remove(Long id, Long owner) throws Exception {
        return mockMvc.perform(delete("/api/jobs/{id}", id).header("Authorization", ownerToken(owner)));
    }
    private String ownerToken(Long owner) {
        return TestAccessTokens.bearer(TestAccessTokens.owner(jwtProperties.secret(), owner));
    }
}
