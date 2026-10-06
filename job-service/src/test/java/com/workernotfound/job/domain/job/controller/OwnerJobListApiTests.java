package com.workernotfound.job.domain.job.controller;

import com.jayway.jsonpath.JsonPath;
import com.workernotfound.job.domain.job.dto.request.OwnerJobSearchRequest;
import com.workernotfound.job.domain.job.entity.JobMatchingSeatReservation;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.repository.JobMatchingSeatReservationRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.service.JobFindService;
import com.workernotfound.job.global.security.JwtProperties;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.TestAccessTokens;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.Matchers;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 점주는 토큰의 회원 ID로 본인 공고만 상태와 관계없이 등록 최신순으로 조회하고, 카드마다 확정 인원을 함께 받는다.
@AutoConfigureMockMvc
class OwnerJobListApiTests extends IntegrationTestSupport {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong(980_000);
    private static final AtomicLong MATCHING_SEQUENCE = new AtomicLong(980_000);
    private static final LocalDateTime REGISTERED_AT = LocalDateTime.of(2026, 1, 1, 9, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobMatchingSeatReservationRepository reservationRepository;

    @Autowired
    private JobFindService jobFindService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void returnsOnlyOwnJobsIncludingPrivateFundingBlockedAndClosedJobs() throws Exception {
        long ownerId = nextOwnerId();
        JobPost pending = saveJob(ownerId, JobStatus.PAYMENT_PENDING);
        JobPost blocked = saveJob(JobPostFixture.open(JobPostFixture.jobPost().ownerId(ownerId).build()), true);
        JobPost matching = saveJob(ownerId, JobStatus.MATCHING);
        JobPost closed = saveJob(ownerId, JobStatus.CLOSED);
        JobPost otherOwners = saveJob(ownerId + 1, JobStatus.OPEN);

        String body = queryAsOwner(ownerId, get("/api/jobs/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(4))
                .andReturn().getResponse().getContentAsString();

        assertThat(jobIds(body))
                .containsExactlyInAnyOrder(pending.getId(), blocked.getId(), matching.getId(), closed.getId())
                .doesNotContain(otherOwners.getId());
        assertThat(statusOf(body, pending)).isEqualTo("PAYMENT_PENDING");
        assertThat(statusOf(body, closed)).isEqualTo("CLOSED");
        assertThat((List<Boolean>) JsonPath.read(body, cardPath(blocked) + ".isFundingBlocked")).containsExactly(true);
        assertThat((List<Boolean>) JsonPath.read(body, cardPath(pending) + ".isFundingBlocked")).containsExactly(false);
    }

    @Test
    void cardShowsScheduleDeadlineAndRecruitCounts() throws Exception {
        long ownerId = nextOwnerId();
        JobPost overnight = saveJob(JobPostFixture.withStatus(JobPostFixture.jobPost()
                .ownerId(ownerId)
                .title("야간 공고")
                .startTime(LocalTime.of(22, 0))
                .endTime(LocalTime.of(6, 0))
                .endTimeNextDay(true)
                .recruitCount(3)
                .build(), JobStatus.OPEN), false);

        queryAsOwner(ownerId, get("/api/jobs/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobs[0].id").value(overnight.getId()))
                .andExpect(jsonPath("$.data.jobs[0].storeName").value("테스트 상점"))
                .andExpect(jsonPath("$.data.jobs[0].title").value("야간 공고"))
                .andExpect(jsonPath("$.data.jobs[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data.jobs[0].workDate").value(overnight.getWorkDate().toString()))
                .andExpect(jsonPath("$.data.jobs[0].startTime").value("22:00:00"))
                .andExpect(jsonPath("$.data.jobs[0].endTime").value("06:00:00"))
                .andExpect(jsonPath("$.data.jobs[0].isEndTimeNextDay").value(true))
                .andExpect(jsonPath("$.data.jobs[0].applicationDeadline").value(Matchers.startsWith(
                        overnight.getApplicationDeadline().toLocalDate().toString())))
                .andExpect(jsonPath("$.data.jobs[0].recruitCount").value(3))
                .andExpect(jsonPath("$.data.jobs[0].confirmedCount").value(0))
                .andExpect(jsonPath("$.data.jobs[0].applicantCount").value(Matchers.nullValue()));
    }

    @Test
    void filtersBySingleStatus() throws Exception {
        long ownerId = nextOwnerId();
        saveJob(ownerId, JobStatus.PAYMENT_PENDING);
        saveJob(ownerId, JobStatus.OPEN);
        JobPost closed = saveJob(ownerId, JobStatus.CLOSED);
        saveJob(ownerId + 1, JobStatus.CLOSED);

        String body = queryAsOwner(ownerId, get("/api/jobs/me").param("status", "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(jobIds(body)).containsExactly(closed.getId());
        queryAsOwner(ownerId, get("/api/jobs/me").param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-002"))
                .andExpect(jsonPath("$.reasons.status").value("유효하지 않은 값입니다."))
                .andExpect(content().string(Matchers.not(Matchers.containsString("JobStatus"))));
    }

    @Test
    void sortsByNewestRegistrationThenHigherId() throws Exception {
        long ownerId = nextOwnerId();
        JobPost older = saveJob(ownerId, JobStatus.OPEN);
        JobPost sameTimeLowerId = saveJob(ownerId, JobStatus.OPEN);
        JobPost sameTimeHigherId = saveJob(ownerId, JobStatus.OPEN);
        JobPost newest = saveJob(ownerId, JobStatus.OPEN);
        setRegisteredAt(older, REGISTERED_AT.minusHours(1));
        setRegisteredAt(sameTimeLowerId, REGISTERED_AT);
        setRegisteredAt(sameTimeHigherId, REGISTERED_AT);
        // 가장 먼저 저장했어도 등록 시각이 가장 늦으면 맨 앞이다.
        setRegisteredAt(newest, REGISTERED_AT.plusHours(1));

        String body = queryAsOwner(ownerId, get("/api/jobs/me"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(jobIds(body)).containsExactly(
                newest.getId(), sameTimeHigherId.getId(), sameTimeLowerId.getId(), older.getId());
    }

    @Test
    void pagesWithDefaultsUpperBoundAndOutOfRangePage() throws Exception {
        long ownerId = nextOwnerId();
        JobPost first = saveJob(ownerId, JobStatus.OPEN);
        JobPost second = saveJob(ownerId, JobStatus.OPEN);
        JobPost third = saveJob(ownerId, JobStatus.OPEN);
        setRegisteredAt(first, REGISTERED_AT);
        setRegisteredAt(second, REGISTERED_AT);
        setRegisteredAt(third, REGISTERED_AT);

        queryAsOwner(ownerId, get("/api/jobs/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
        String secondPage = queryAsOwner(ownerId, get("/api/jobs/me").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(jobIds(secondPage)).containsExactly(first.getId());

        queryAsOwner(ownerId, get("/api/jobs/me").param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobs").isEmpty())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        queryAsOwner(ownerId, get("/api/jobs/me").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobs.length()").value(3));
        for (String[] invalid : List.of(
                new String[]{"size", "101"}, new String[]{"size", "0"}, new String[]{"page", "-1"})) {
            queryAsOwner(ownerId, get("/api/jobs/me").param(invalid[0], invalid[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("GLOBAL-400-002"));
        }
    }

    @Test
    void confirmedCountIncludesOnlyConsumedReservations() throws Exception {
        long ownerId = nextOwnerId();
        JobPost mixed = saveJob(JobPostFixture.withStatus(
                JobPostFixture.jobPost().ownerId(ownerId).recruitCount(5).build(), JobStatus.MATCHING), false);
        JobPost reservedOnly = saveJob(ownerId, JobStatus.OPEN);
        JobPost noReservation = saveJob(ownerId, JobStatus.OPEN);
        saveReservation(mixed, Outcome.RESERVED);
        saveReservation(mixed, Outcome.CONSUMED);
        saveReservation(mixed, Outcome.CONSUMED);
        saveReservation(mixed, Outcome.RELEASED);
        saveReservation(mixed, Outcome.EXPIRED);
        saveReservation(reservedOnly, Outcome.RESERVED);

        String body = queryAsOwner(ownerId, get("/api/jobs/me"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(confirmedCountOf(body, mixed)).isEqualTo(2);
        assertThat(confirmedCountOf(body, reservedOnly)).isZero();
        assertThat(confirmedCountOf(body, noReservation)).isZero();
    }

    // 확정 인원은 페이지의 공고 ID로 묶어 한 번에 센다. 공고 수가 늘어도 쿼리 수가 늘지 않는다.
    @Test
    void countsConfirmedSeatsWithoutQueryPerJob() {
        long ownerId = nextOwnerId();
        for (int i = 0; i < 6; i++) {
            JobPost post = saveJob(ownerId, JobStatus.OPEN);
            saveReservation(post, Outcome.CONSUMED);
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        statistics.setStatisticsEnabled(true);
        try {
            // 페이지가 가득 차 전체 건수 쿼리까지 실행되는 경우: 페이지 조회, 건수, 확정 인원 집계
            var response = jobFindService.findOwnerJobs(ownerId, new OwnerJobSearchRequest(null, 0, 5));

            assertThat(response.jobs()).hasSize(5).allSatisfy(card -> assertThat(card.confirmedCount()).isEqualTo(1));
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        } finally {
            statistics.setStatisticsEnabled(false);
            statistics.clear();
        }
    }

    @Test
    void requiresOwnerTokenWithSameBoundaryAsOtherProtectedApis() throws Exception {
        long ownerId = nextOwnerId();
        saveJob(ownerId, JobStatus.OPEN);

        mockMvc.perform(get("/api/jobs/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
        mockMvc.perform(get("/api/jobs/me")
                        .header("Authorization", TestAccessTokens.bearer(
                                TestAccessTokens.worker(jwtProperties.secret(), ownerId))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GLOBAL-403-001"));
        // 잘못된 토큰은 익명 요청과 같이 처리되어 인증이 필요한 경로에서 401이다.
        mockMvc.perform(get("/api/jobs/me").header("Authorization", "Bearer forged.token.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
    }

    // 점주 ID는 토큰으로만 정한다. 다른 점주 ID를 요청 값으로 보내도 무시된다.
    @Test
    void ignoresOwnerIdInRequest() throws Exception {
        long ownerId = nextOwnerId();
        JobPost own = saveJob(ownerId, JobStatus.OPEN);
        saveJob(ownerId + 1, JobStatus.OPEN);

        String body = queryAsOwner(ownerId, get("/api/jobs/me")
                        .param("ownerId", String.valueOf(ownerId + 1))
                        .param("memberId", String.valueOf(ownerId + 1)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(jobIds(body)).containsExactly(own.getId());
    }

    @Test
    void ownerListPathAndNumericDetailPathDoNotInterfere() throws Exception {
        long ownerId = nextOwnerId();
        JobPost open = saveJob(ownerId, JobStatus.OPEN);
        JobPost pending = saveJob(ownerId, JobStatus.PAYMENT_PENDING);

        queryAsOwner(ownerId, get("/api/jobs/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobs").isArray())
                .andExpect(jsonPath("$.data.address").doesNotExist());
        mockMvc.perform(get("/api/jobs/{id}", open.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(open.getId()))
                .andExpect(jsonPath("$.data.address").value("서울시 마포구"));
        mockMvc.perform(get("/api/jobs/{id}", pending.getId())
                        .header("Authorization", TestAccessTokens.bearer(
                                TestAccessTokens.owner(jwtProperties.secret(), ownerId + 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB-404-001"));
        queryAsOwner(ownerId, get("/api/jobs/{id}", pending.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(pending.getId()));
    }

    // 다른 점주로 ownerId + 1을 쓰므로 테스트마다 두 칸씩 띄워 다른 테스트의 점주와 겹치지 않게 한다.
    private static long nextOwnerId() {
        return OWNER_SEQUENCE.addAndGet(2);
    }

    private ResultActions queryAsOwner(long ownerId, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", TestAccessTokens.bearer(
                TestAccessTokens.owner(jwtProperties.secret(), ownerId))));
    }

    private JobPost saveJob(long ownerId, JobStatus status) {
        return saveJob(JobPostFixture.withStatus(JobPostFixture.jobPost().ownerId(ownerId).build(), status), false);
    }

    private JobPost saveJob(JobPost post, boolean isFundingBlocked) {
        if (isFundingBlocked) {
            post.blockFunding();
        }
        return jobPostRepository.save(post);
    }

    private void setRegisteredAt(JobPost post, LocalDateTime registeredAt) {
        jdbcTemplate.update("update job_posts set created_at = ? where id = ?", registeredAt, post.getId());
    }

    private enum Outcome { RESERVED, CONSUMED, RELEASED, EXPIRED }

    private void saveReservation(JobPost post, Outcome outcome) {
        long matchingId = MATCHING_SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        JobMatchingSeatReservation reservation = JobMatchingSeatReservation.builder()
                .jobPostId(post.getId())
                .matchingId(matchingId)
                .applicationId(matchingId)
                .workerMemberId(matchingId)
                .idempotencyKey(UUID.randomUUID().toString())
                .jobVersion(post.getVersion())
                .ownerMemberId(post.getOwnerId())
                .workDate(post.getWorkDate())
                .startTime(post.getStartTime())
                .endTime(post.getEndTime())
                .endTimeNextDay(post.isEndTimeNextDay())
                .lockedAmount(90_000L)
                .currency("KRW")
                .reservedAt(now)
                .expiresAt(now.plusMinutes(10))
                .build();
        switch (outcome) {
            case RESERVED -> { }
            case CONSUMED -> reservation.consume(UUID.randomUUID().toString(), now);
            case RELEASED -> reservation.release(UUID.randomUUID().toString(), now);
            case EXPIRED -> reservation.expire(now);
        }
        reservationRepository.save(reservation);
    }

    private static List<Long> jobIds(String body) {
        List<Number> ids = JsonPath.read(body, "$.data.jobs[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private static String cardPath(JobPost post) {
        return "$.data.jobs[?(@.id == " + post.getId() + ")]";
    }

    private static String statusOf(String body, JobPost post) {
        List<String> statuses = JsonPath.read(body, cardPath(post) + ".status");
        return statuses.get(0);
    }

    private static long confirmedCountOf(String body, JobPost post) {
        List<Number> counts = JsonPath.read(body, cardPath(post) + ".confirmedCount");
        return counts.get(0).longValue();
    }
}
