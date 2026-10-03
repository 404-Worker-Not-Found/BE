package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class MatchingSeatReservationApiTests extends IntegrationTestSupport {

    private static final String INTERNAL_SECRET = "test-internal-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Test
    void reservesSeatWithContractFields() throws Exception {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost()
                .startTime(LocalTime.of(23, 0))
                .endTime(LocalTime.of(1, 15))
                .endTimeNextDay(true)
                .build());

        reserve(jobPost.getId(), newKey(), body(11L, 21L, 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reservationId").value(isA(String.class)))
                .andExpect(jsonPath("$.data.jobPostId").value(jobPost.getId()))
                .andExpect(jsonPath("$.data.jobVersion").value(1))
                .andExpect(jsonPath("$.data.ownerMemberId").value(7))
                .andExpect(jsonPath("$.data.workDate").value(jobPost.getWorkDate().toString()))
                .andExpect(jsonPath("$.data.startTime").value("23:00:00"))
                .andExpect(jsonPath("$.data.endTime").value("01:15:00"))
                .andExpect(jsonPath("$.data.endTimeNextDay").value(true))
                .andExpect(jsonPath("$.data.lockedAmount").value(22_500))
                .andExpect(jsonPath("$.data.currency").value("KRW"))
                .andExpect(jsonPath("$.data.reservedAt").value(notNullValue()))
                .andExpect(jsonPath("$.data.expiresAt").value(notNullValue()));
    }

    @Test
    void rejectsSameKeyForDifferentMatchingWithConflict() throws Exception {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost().recruitCount(3).build());
        String key = newKey();
        reserve(jobPost.getId(), key, body(12L, 22L, 100L)).andExpect(status().isOk());

        reserve(jobPost.getId(), key, body(13L, 22L, 100L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-004"));
    }

    @Test
    void returnsDomainCodeWhenNoSeatRemains() throws Exception {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost().build());
        reserve(jobPost.getId(), newKey(), body(14L, 24L, 100L)).andExpect(status().isOk());

        reserve(jobPost.getId(), newKey(), body(15L, 25L, 101L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-007"));
    }

    @Test
    void requiresInternalSecret() throws Exception {
        mockMvc.perform(post("/api/jobs/internal/{jobPostId}/matching-seat-reservations", 1L)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L, 1L, 1L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
    }

    @Test
    void validatesRequest() throws Exception {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost().build());

        reserve(jobPost.getId(), newKey(), "{\"matchingId\":1,\"workerMemberId\":1}")
                .andExpect(status().isBadRequest());
        reserve(jobPost.getId(), newKey(), body(0L, 1L, 1L))
                .andExpect(status().isBadRequest());
        reserve(jobPost.getId(), "has space", body(1L, 1L, 1L))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/jobs/internal/{jobPostId}/matching-seat-reservations", jobPost.getId())
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L, 1L, 1L)))
                .andExpect(status().isBadRequest());
    }

    private ResultActions reserve(Long jobPostId, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/jobs/internal/{jobPostId}/matching-seat-reservations", jobPostId)
                .header("X-Internal-Secret", INTERNAL_SECRET)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String body(Long matchingId, Long applicationId, Long workerMemberId) {
        return "{\"matchingId\":%d,\"applicationId\":%d,\"workerMemberId\":%d}"
                .formatted(matchingId, applicationId, workerMemberId);
    }

    private String newKey() {
        return "seat-api-" + UUID.randomUUID();
    }
}
