package com.workernotfound.job.domain.job.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// TTL에 마이크로초 미만 값이 있어도 만료 시각은 절삭되어 최초 응답·DB·재요청이 같아야 한다.
@AutoConfigureMockMvc
@TestPropertySource(properties = "job.matching-seat-reservation.ttl=PT10M0.000000999S")
class MatchingSeatReservationSubMicrosecondTtlTests extends IntegrationTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void truncatesExpiryAndKeepsSnapshotIdentical() throws Exception {
        clock.fixAt(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789));
        JobPost jobPost = jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().build()));
        String key = "seat-sub-micro-" + UUID.randomUUID();

        JsonNode first = reserve(jobPost.getId(), key);
        clock.advance(Duration.ofMinutes(3));
        JsonNode retry = reserve(jobPost.getId(), key);

        assertThat(retry).isEqualTo(first);
        LocalDateTime reservedAt = LocalDateTime.parse(first.get("reservedAt").asText());
        LocalDateTime expiresAt = LocalDateTime.parse(first.get("expiresAt").asText());
        assertThat(expiresAt).isEqualTo(reservedAt.plusMinutes(10));
        assertThat(expiresAt.getNano() % 1_000).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select expires_at from job_matching_seat_reservations where id = ?",
                LocalDateTime.class, Long.valueOf(first.get("reservationId").asText())))
                .isEqualTo(expiresAt);
    }

    private JsonNode reserve(Long jobPostId, String key) throws Exception {
        String content = mockMvc.perform(post("/api/jobs/internal/{jobPostId}/matching-seat-reservations", jobPostId)
                        .header("X-Internal-Secret", "test-internal-secret")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"matchingId\":1,\"applicationId\":1,\"workerMemberId\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(content).get("data");
    }
}
