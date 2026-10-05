package com.workernotfound.job.domain.job.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.service.MatchingSeatReservationCommandService;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 나노초가 포함된 시각에서 최초 응답, DB 저장값, 같은 키 재요청이 같은 예약 시각을 갖는지 HTTP 경계에서 검증한다.
@AutoConfigureMockMvc
class MatchingSeatReservationTimePrecisionTests extends IntegrationTestSupport {

    private static final String INTERNAL_SECRET = "test-internal-secret";
    private static final AtomicLong IDS = new AtomicLong(700_000);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private MatchingSeatReservationCommandService commandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    private Instant issuedAt;

    @BeforeEach
    void fixClockWithNanoseconds() {
        issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusNanos(123_456_789);
        clock.fixAt(issuedAt);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void sameKeyReturnsIdenticalSnapshotFromResponseDatabaseAndRetry() throws Exception {
        JobPost jobPost = saveJob(1);
        String key = newKey();
        String body = body(IDS.incrementAndGet(), IDS.incrementAndGet());

        JsonNode first = data(reserve(jobPost.getId(), key, body));
        clock.advance(Duration.ofMinutes(5));
        JsonNode retry = data(reserve(jobPost.getId(), key, body));

        assertThat(retry).isEqualTo(first);
        LocalDateTime reservedAt = LocalDateTime.parse(first.get("reservedAt").asText());
        LocalDateTime expiresAt = LocalDateTime.parse(first.get("expiresAt").asText());
        LocalDateTime issued = LocalDateTime.ofInstant(issuedAt, clock.getZone());
        assertThat(reservedAt).isEqualTo(issued.truncatedTo(ChronoUnit.MICROS));
        assertThat(expiresAt).isEqualTo(reservedAt.plusMinutes(10));
        assertThat(reservedAt.getNano() % 1_000).isZero();
        assertThat(expiresAt.getNano() % 1_000).isZero();
        assertThat(storedTime(first, "reserved_at")).isEqualTo(reservedAt);
        assertThat(storedTime(first, "expires_at")).isEqualTo(expiresAt);
    }

    @Test
    void confirmIsAllowedJustBeforeExpiresAtAndRejectedAtAndAfterIt() throws Exception {
        JobPost jobPost = saveJob(3);
        JsonNode beforeExpiry = data(reserve(jobPost.getId(), newKey(), body(IDS.incrementAndGet(), IDS.incrementAndGet())));
        JsonNode atExpiry = data(reserve(jobPost.getId(), newKey(), body(IDS.incrementAndGet(), IDS.incrementAndGet())));
        JsonNode afterExpiry = data(reserve(jobPost.getId(), newKey(), body(IDS.incrementAndGet(), IDS.incrementAndGet())));
        Instant expiresAt = expiresAt(beforeExpiry);

        clock.fixAt(expiresAt.minusNanos(1));
        confirm(jobPost.getId(), beforeExpiry, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONSUMED"));

        clock.fixAt(expiresAt);
        confirm(jobPost.getId(), atExpiry, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-009"));

        clock.fixAt(expiresAt.plusNanos(1));
        confirm(jobPost.getId(), afterExpiry, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-009"));
    }

    @Test
    void expirySweepUsesTheSameBoundaryWithNanosecondClock() throws Exception {
        JobPost jobPost = saveJob(1);
        JsonNode reservation = data(reserve(jobPost.getId(), newKey(), body(IDS.incrementAndGet(), IDS.incrementAndGet())));
        Instant expiresAt = expiresAt(reservation);

        // MySQL이 나노초 인자를 반올림하면 이 시각에 미리 만료될 수 있다.
        clock.fixAt(expiresAt.minusNanos(1));
        assertThat(commandService.expireOverdue(jobPost.getId())).isZero();

        clock.fixAt(expiresAt);
        assertThat(commandService.expireOverdue(jobPost.getId())).isOne();
    }

    @Test
    void consumedReservationConfirmRetrySucceedsAfterExpiresAt() throws Exception {
        JobPost jobPost = saveJob(1);
        JsonNode reservation = data(reserve(jobPost.getId(), newKey(), body(IDS.incrementAndGet(), IDS.incrementAndGet())));
        String confirmKey = newKey();
        confirm(jobPost.getId(), reservation, confirmKey).andExpect(status().isOk());

        clock.fixAt(expiresAt(reservation).plus(Duration.ofHours(1)));
        commandService.expireOverdue(jobPost.getId());

        confirm(jobPost.getId(), reservation, confirmKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONSUMED"));
    }

    private Instant expiresAt(JsonNode reservation) {
        return LocalDateTime.parse(reservation.get("expiresAt").asText()).atZone(clock.getZone()).toInstant();
    }

    // JPA 영속성 컨텍스트를 거치지 않고 DB에 저장된 값을 읽는다.
    private LocalDateTime storedTime(JsonNode reservation, String column) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from job_matching_seat_reservations where id = ?",
                LocalDateTime.class,
                Long.valueOf(reservation.get("reservationId").asText()));
    }

    private ResultActions confirm(Long jobPostId, JsonNode reservation, String key) throws Exception {
        return mockMvc.perform(post("/api/jobs/internal/{jobPostId}/matching-seat-reservations/{reservationId}/confirm",
                        jobPostId, reservation.get("reservationId").asText())
                .header("X-Internal-Secret", INTERNAL_SECRET)
                .header("Idempotency-Key", key));
    }

    private ResultActions reserve(Long jobPostId, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/jobs/internal/{jobPostId}/matching-seat-reservations", jobPostId)
                .header("X-Internal-Secret", INTERNAL_SECRET)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode data(ResultActions result) throws Exception {
        String content = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(content).get("data");
    }

    private JobPost saveJob(int recruitCount) {
        return jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().recruitCount(recruitCount).build()));
    }

    private String body(long matchingId, long applicationId) {
        return "{\"matchingId\":%d,\"applicationId\":%d,\"workerMemberId\":100}".formatted(matchingId, applicationId);
    }

    private String newKey() {
        return "seat-precision-" + UUID.randomUUID();
    }
}
