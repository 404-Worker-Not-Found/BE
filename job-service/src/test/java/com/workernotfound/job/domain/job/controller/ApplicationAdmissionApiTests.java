package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.UrgencyLevel;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ApplicationAdmissionApiTests extends IntegrationTestSupport {

    private static final String INTERNAL_SECRET = "test-internal-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Test
    void issuesAdmissionWithJobSnapshot() throws Exception {
        JobPost jobPost = saveJobPost(JobStatus.OPEN, LocalDateTime.now().plusHours(1));

        requestAdmission(jobPost.getId(), newKey(), 100L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.admissionId").value(notNullValue()))
                .andExpect(jsonPath("$.data.jobPostId").value(jobPost.getId()))
                .andExpect(jsonPath("$.data.jobVersion").value(jobPost.getVersion()))
                .andExpect(jsonPath("$.data.ownerMemberId").value(7))
                .andExpect(jsonPath("$.data.categoryId").value(1))
                .andExpect(jsonPath("$.data.workDate").value(jobPost.getWorkDate().toString()))
                .andExpect(jsonPath("$.data.startTime").value("09:00:00"))
                .andExpect(jsonPath("$.data.latitude").value(37.5665))
                .andExpect(jsonPath("$.data.admittedAt").value(notNullValue()))
                .andExpect(jsonPath("$.data.expiresAt").value(notNullValue()));
    }

    @Test
    void returnsSameAdmissionForSameIdempotencyKey() throws Exception {
        JobPost jobPost = saveJobPost(JobStatus.OPEN, LocalDateTime.now().plusHours(1));
        String key = newKey();

        String first = requestAdmission(jobPost.getId(), key, 100L)
                .andReturn().getResponse().getContentAsString();
        Integer firstId = com.jayway.jsonpath.JsonPath.read(first, "$.data.admissionId");

        requestAdmission(jobPost.getId(), key, 100L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.admissionId").value(firstId));
    }

    @Test
    void rejectsIdempotencyKeyReusedForDifferentWorker() throws Exception {
        JobPost jobPost = saveJobPost(JobStatus.OPEN, LocalDateTime.now().plusHours(1));
        String key = newKey();
        requestAdmission(jobPost.getId(), key, 100L).andExpect(status().isOk());

        requestAdmission(jobPost.getId(), key, 200L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-004"));
    }

    @Test
    void returnsNotFoundForMissingJob() throws Exception {
        requestAdmission(999_999L, newKey(), 100L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB-404-001"));
    }

    @Test
    void returnsConflictForClosedJob() throws Exception {
        JobPost jobPost = saveJobPost(JobStatus.CLOSED, LocalDateTime.now().plusHours(1));

        requestAdmission(jobPost.getId(), newKey(), 100L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-001"));
    }

    @Test
    void returnsConflictAfterApplicationDeadline() throws Exception {
        JobPost jobPost = saveJobPost(JobStatus.OPEN, LocalDateTime.now().minusMinutes(1));

        requestAdmission(jobPost.getId(), newKey(), 100L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-002"));
    }

    @Test
    void rejectsMissingIdempotencyKey() throws Exception {
        JobPost jobPost = saveJobPost(JobStatus.OPEN, LocalDateTime.now().plusHours(1));

        mockMvc.perform(post("/api/jobs/internal/{jobPostId}/application-admissions", jobPost.getId())
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerMemberId\":100}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsTooLongIdempotencyKey() throws Exception {
        JobPost jobPost = saveJobPost(JobStatus.OPEN, LocalDateTime.now().plusHours(1));

        requestAdmission(jobPost.getId(), "k".repeat(101), 100L)
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingWorkerMemberId() throws Exception {
        JobPost jobPost = saveJobPost(JobStatus.OPEN, LocalDateTime.now().plusHours(1));

        mockMvc.perform(post("/api/jobs/internal/{jobPostId}/application-admissions", jobPost.getId())
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions requestAdmission(Long jobPostId, String key, Long workerMemberId) throws Exception {
        return mockMvc.perform(post("/api/jobs/internal/{jobPostId}/application-admissions", jobPostId)
                .header("X-Internal-Secret", INTERNAL_SECRET)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workerMemberId\":" + workerMemberId + "}"));
    }

    private String newKey() {
        return "admission-" + UUID.randomUUID();
    }

    private JobPost saveJobPost(JobStatus status, LocalDateTime applicationDeadline) {
        JobPost jobPost = JobPost.builder()
                .businessId(1L)
                .ownerId(7L)
                .categoryId(1L)
                .storeName("테스트 상점")
                .address("서울시 마포구")
                .title("테스트 공고")
                .description("테스트 설명")
                .workDate(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .endTimeNextDay(false)
                .baseHourlyWage(10_000)
                .recruitCount(1)
                .latitude(new BigDecimal("37.5665000"))
                .longitude(new BigDecimal("126.9780000"))
                .urgencyLevel(UrgencyLevel.MEDIUM)
                .applicationDeadline(applicationDeadline)
                .build();
        if (status != JobStatus.OPEN) {
            ReflectionTestUtils.setField(jobPost, "status", status);
        }
        return jobPostRepository.save(jobPost);
    }
}
