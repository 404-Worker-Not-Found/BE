package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.service.JobCommandService;
import com.workernotfound.job.domain.job.service.PaymentOrderDispatcher;
import com.workernotfound.job.global.security.JwtProperties;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import com.workernotfound.job.support.StubPaymentServer;
import com.workernotfound.job.support.TestAccessTokens;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 점주는 본인 공고의 결제 주문 생성 상태와 paymentOrderId를 조회한다. 생성 완료 전에는 paymentOrderId가 null이다.
@AutoConfigureMockMvc
class JobPaymentOrderQueryApiTests extends IntegrationTestSupport {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong(970_000);
    private static final StubPaymentServer SERVER = StubPaymentServer.start();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobCommandService jobCommandService;

    @Autowired
    private PaymentOrderDispatcher dispatcher;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobPaymentOrderCommandRepository commandRepository;

    @Autowired
    private JwtProperties jwtProperties;

    @DynamicPropertySource
    static void registerPaymentServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("job.payment-service.base-url", SERVER::baseUrl);
    }

    @BeforeEach
    void setUp() {
        SERVER.reset();
    }

    @AfterAll
    void stopServer() {
        SERVER.close();
    }

    @Test
    void ownerSeesPendingCreationWithNullOrderIdThenCreatedOrderId() throws Exception {
        long ownerId = OWNER_SEQUENCE.incrementAndGet();
        JobPaymentOrderCommand command = createJob(ownerId);

        query(command.getJobPostId(), TestAccessTokens.owner(jwtProperties.secret(), ownerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobPostId").value(command.getJobPostId()))
                .andExpect(jsonPath("$.data.jobStatus").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.orderCreationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.paymentOrderId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.paymentJobVersion").value(1))
                .andExpect(jsonPath("$.data.amount").value(command.getAmount()))
                .andExpect(jsonPath("$.data.currency").value("KRW"));

        dispatcher.dispatch(command.getId());

        query(command.getJobPostId(), TestAccessTokens.owner(jwtProperties.secret(), ownerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobStatus").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.orderCreationStatus").value("CREATED"))
                .andExpect(jsonPath("$.data.paymentOrderId").value(SERVER.issuedOrderId(command.getIdempotencyKey())))
                .andExpect(jsonPath("$.data.paymentJobVersion").value(1))
                .andExpect(jsonPath("$.data.amount").value(command.getAmount()));
    }

    @Test
    void hidesOtherOwnersJobAndRequiresOwnerRole() throws Exception {
        long ownerId = OWNER_SEQUENCE.incrementAndGet();
        JobPaymentOrderCommand command = createJob(ownerId);

        query(command.getJobPostId(), TestAccessTokens.owner(jwtProperties.secret(), ownerId + 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB-404-001"));
        query(command.getJobPostId(), TestAccessTokens.worker(jwtProperties.secret(), ownerId))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/jobs/{id}/payment-order", command.getJobPostId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportsNotRequestedForJobCreatedBeforePaymentOrders() throws Exception {
        // 결제 대기 생성 이전에 만들어진 공개 공고처럼 주문 생성 명령이 없다.
        JobPost legacy = jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().build()));

        query(legacy.getId(), TestAccessTokens.owner(jwtProperties.secret(), legacy.getOwnerId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.orderCreationStatus").value("NOT_REQUESTED"))
                .andExpect(jsonPath("$.data.paymentOrderId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.amount").value(Matchers.nullValue()));
    }

    private ResultActions query(Long jobPostId, String token) throws Exception {
        return mockMvc.perform(get("/api/jobs/{id}/payment-order", jobPostId)
                .header("Authorization", TestAccessTokens.bearer(token)));
    }

    private JobPaymentOrderCommand createJob(long ownerId) {
        LocalDate workDate = LocalDate.now().plusDays(2);
        Long jobPostId = jobCommandService.create(ownerId, new CreateJobRequest(
                1L, 1L, "테스트 상점", "서울시 마포구", "주문 조회 공고", "설명",
                workDate, LocalTime.of(9, 0), LocalTime.of(18, 0), false, 10_320, null, 2,
                new BigDecimal("37.5665000"), new BigDecimal("126.9780000"), "MEDIUM",
                LocalDateTime.of(workDate.minusDays(1), LocalTime.NOON)));
        return commandRepository.findByJobPostIdOrderByIssueSequenceAsc(jobPostId).get(0);
    }
}
