package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.service.JobCommandService;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.global.security.JwtProperties;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.TestAccessTokens;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신규 공고의 비공개 생성과 전체 예치 예정액 검증.
 *
 * <p>요청 검증의 최저 시급(10,320원) 때문에 HTTP 요청으로는 전체 예치 예정액이 100원 미만이 될 수 없다. 금액 경계는
 * 컨트롤러 요청 검증 뒤에 실행되는 실제 생성 서비스로 확인한다.
 */
@AutoConfigureMockMvc
class JobCreationApiTests extends IntegrationTestSupport {

    // 다른 테스트가 쓰지 않는 업종('기타')으로 검색 범위를 좁힌다.
    private static final long SEARCH_CATEGORY_ID = 7L;
    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong(910_000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobCommandService jobCommandService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsJobPrivatelyAsPaymentPending() throws Exception {
        long ownerId = nextOwnerId();

        Long jobPostId = createThroughApi(ownerId);

        JobPost saved = jobPostRepository.findById(jobPostId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        assertThat(saved.getOwnerId()).isEqualTo(ownerId);
    }

    @Test
    void searchExcludesPaymentPendingJob() throws Exception {
        Long pendingId = createThroughApi(nextOwnerId());
        JobPost open = jobPostRepository.save(JobPostFixture.open(
                JobPostFixture.jobPost().categoryId(SEARCH_CATEGORY_ID).build()));

        String content = mockMvc.perform(get("/api/jobs/search")
                        .param("categoryIds", String.valueOf(SEARCH_CATEGORY_ID))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Integer> ids = com.jayway.jsonpath.JsonPath.read(content, "$.data.jobs[*].id");
        assertThat(ids).contains(open.getId().intValue()).doesNotContain(pendingId.intValue());
    }

    // 요청 제약은 ControllerDocs 인터페이스에 선언한다. 등록 본문과 검색 조건이 HTTP 경계에서 계속 검증되는지 확인한다.
    @Test
    void validatesCreateBodyAndSearchConditionAtHttpBoundary() throws Exception {
        long ownerId = nextOwnerId();
        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", TestAccessTokens.bearer(
                                TestAccessTokens.owner(jwtProperties.secret(), ownerId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody().replace("\"baseHourlyWage\":10320", "\"baseHourlyWage\":10319")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GLOBAL-400-002"));
        mockMvc.perform(get("/api/jobs/search").param("size", "101"))
                .andExpect(status().isBadRequest());

        assertThat(countJobsOf(ownerId)).isZero();
    }

    @Test
    void acceptsTotalDepositOfExactlyOneHundredWon() {
        long ownerId = nextOwnerId();
        // 1분 × 시급 6,000원 / 60 = 100원
        Long jobPostId = jobCommandService.create(ownerId, request(LocalTime.of(9, 1), 6_000, 1));

        assertThat(jobPostRepository.findById(jobPostId).orElseThrow().getStatus())
                .isEqualTo(JobStatus.PAYMENT_PENDING);
    }

    @Test
    void rejectsTotalDepositBelowOneHundredWonWithoutSaving() {
        long ownerId = nextOwnerId();
        // 1분 × 시급 5,999원 / 60 = 99.98원 → 99원. 100원으로 올리지 않고 거절한다.
        assertInvalidAmount(() -> jobCommandService.create(ownerId, request(LocalTime.of(9, 1), 5_999, 1)));

        assertThat(countJobsOf(ownerId)).isZero();
    }

    @Test
    void rejectsTotalDepositOverflowWithoutSaving() {
        long ownerId = nextOwnerId();
        assertInvalidAmount(() -> jobCommandService.create(
                ownerId, request(LocalTime.of(18, 0), Integer.MAX_VALUE, Integer.MAX_VALUE)));

        assertThat(countJobsOf(ownerId)).isZero();
    }

    private Long createThroughApi(long ownerId) throws Exception {
        String content = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", TestAccessTokens.bearer(
                                TestAccessTokens.owner(jwtProperties.secret(), ownerId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(content, "$.data")).longValue();
    }

    private String requestBody() {
        LocalDate workDate = LocalDate.now().plusDays(2);
        return """
                {"businessId":1,"categoryId":%d,"storeName":"테스트 상점","address":"서울시 마포구",
                 "title":"결제 대기 공고","description":"설명","workDate":"%s","startTime":"09:00","endTime":"18:00",
                 "isEndTimeNextDay":false,"baseHourlyWage":10320,"recruitCount":2,
                 "latitude":37.5665,"longitude":126.978,"urgencyLevel":"MEDIUM","applicationDeadline":"%s"}
                """.formatted(SEARCH_CATEGORY_ID, workDate, workDate.minusDays(1).atTime(12, 0));
    }

    private CreateJobRequest request(LocalTime endTime, int baseHourlyWage, int recruitCount) {
        LocalDate workDate = LocalDate.now().plusDays(2);
        return new CreateJobRequest(
                1L, SEARCH_CATEGORY_ID, "테스트 상점", "서울시 마포구", "금액 경계 공고", "설명",
                workDate, LocalTime.of(9, 0), endTime, false, baseHourlyWage, null, recruitCount,
                new BigDecimal("37.5665000"), new BigDecimal("126.9780000"), "MEDIUM",
                LocalDateTime.of(workDate.minusDays(1), LocalTime.NOON));
    }

    private void assertInvalidAmount(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(JobErrorCode.INVALID_WAGE_AMOUNT);
    }

    private int countJobsOf(long ownerId) {
        return jdbcTemplate.queryForObject("select count(*) from job_posts where owner_id = ?", Integer.class, ownerId);
    }

    private long nextOwnerId() {
        return OWNER_SEQUENCE.incrementAndGet();
    }
}
