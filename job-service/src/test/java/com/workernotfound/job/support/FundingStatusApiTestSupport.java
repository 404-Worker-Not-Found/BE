package com.workernotfound.job.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.workernotfound.job.domain.job.dto.request.CreateJobRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.repository.JobFundingStatusReceiptRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentFundingRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.JobStatusHistoryRepository;
import com.workernotfound.job.domain.job.service.JobCommandService;
import com.workernotfound.job.domain.job.service.PaymentOrderDispatcher;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예치 상태 수신 API 통합 테스트의 공통 경로.
 *
 * <p>공고는 실제 등록 서비스로 {@code PAYMENT_PENDING}과 주문 생성 명령을 함께 저장하고, 실제 소켓의 payment-service 대역에 명령을
 * 전송해 검증된 주문을 연결한다. 그 뒤 내부 API를 MockMvc로 호출한다. 실제 PG 결제와 payment-service DB는 쓰지 않는다.
 */
@AutoConfigureMockMvc
public abstract class FundingStatusApiTestSupport extends IntegrationTestSupport {

    protected static final String INTERNAL_SECRET = "test-internal-secret";
    protected static final Duration CALL_TIMEOUT = Duration.ofMillis(1500);
    protected static final Duration BASE_DELAY = Duration.ofSeconds(2);
    // 같은 설정의 테스트 클래스들이 Spring 컨텍스트와 이 대역 주소를 공유하므로 클래스 종료 시 닫지 않는다.
    protected static final StubPaymentServer PAYMENT_SERVER = StubPaymentServer.start();
    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong(960_000);
    private static final AtomicLong EXTERNAL_ID_SEQUENCE = new AtomicLong(960_000);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JobCommandService jobCommandService;

    @Autowired
    protected PaymentOrderDispatcher paymentOrderDispatcher;

    @Autowired
    protected JobPostRepository jobPostRepository;

    @Autowired
    protected JobPaymentOrderCommandRepository commandRepository;

    @Autowired
    protected JobStatusHistoryRepository historyRepository;

    @Autowired
    protected JobFundingStatusReceiptRepository receiptRepository;

    @Autowired
    protected JobPaymentFundingRepository fundingRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    @DynamicPropertySource
    static void registerPaymentServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("job.payment-service.base-url", PAYMENT_SERVER::baseUrl);
        registry.add("job.payment-service.connect-timeout", () -> "500ms");
        registry.add("job.payment-service.read-timeout", () -> "500ms");
        registry.add("job.payment-service.call-timeout", () -> CALL_TIMEOUT.toMillis() + "ms");
        registry.add("job.payment-order.lease-duration", () -> "3s");
        registry.add("job.payment-order.retry-base-delay", () -> BASE_DELAY.toSeconds() + "s");
        registry.add("job.payment-order.retry-max-delay", () -> "5s");
    }

    @BeforeEach
    void resetPaymentServerAndClock() {
        PAYMENT_SERVER.reset();
        clock.reset();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    public record LinkedJob(Long jobPostId, String orderId, Long paymentJobVersion, Long ownerMemberId, Long amount) {
    }

    // 모레 09:00~18:00, 시급 10,320원, 지원 마감은 전날 정오인 결제 대기 공고를 만들고 검증된 주문을 연결한다.
    protected LinkedJob createLinkedJob(int recruitCount) {
        LocalDate workDate = LocalDate.now().plusDays(2);
        return createLinkedJob(recruitCount, workDate, LocalTime.of(9, 0), workDate.minusDays(1).atTime(LocalTime.NOON));
    }

    protected LinkedJob createLinkedJob(
            int recruitCount,
            LocalDate workDate,
            LocalTime startTime,
            LocalDateTime applicationDeadline
    ) {
        JobPaymentOrderCommand command = createPendingJob(recruitCount, workDate, startTime, applicationDeadline);
        assertThat(paymentOrderDispatcher.dispatch(command.getId())).isTrue();
        String orderId = PAYMENT_SERVER.issuedOrderId(command.getIdempotencyKey());
        assertThat(jobPost(command.getJobPostId()).getPaymentOrderId()).isEqualTo(orderId);
        return new LinkedJob(
                command.getJobPostId(), orderId, command.getJobVersion(), command.getOwnerMemberId(), command.getAmount());
    }

    protected JobPaymentOrderCommand createPendingJob(int recruitCount) {
        LocalDate workDate = LocalDate.now().plusDays(2);
        return createPendingJob(recruitCount, workDate, LocalTime.of(9, 0), workDate.minusDays(1).atTime(LocalTime.NOON));
    }

    protected JobPaymentOrderCommand createPendingJob(
            int recruitCount,
            LocalDate workDate,
            LocalTime startTime,
            LocalDateTime applicationDeadline
    ) {
        Long jobPostId = jobCommandService.create(OWNER_SEQUENCE.incrementAndGet(), new CreateJobRequest(
                1L, 1L, "테스트 상점", "서울시 마포구", "예치 공고", "설명",
                workDate, startTime, startTime.plusHours(4), false, 10_320, null, recruitCount,
                new BigDecimal("37.5665000"), new BigDecimal("126.9780000"), "MEDIUM", applicationDeadline));
        return commandRepository.findByJobPostIdOrderByIssueSequenceAsc(jobPostId).get(0);
    }

    // payment-service의 FundingJobClient와 같은 본문. 금액은 DECIMAL(19,2)라 소수 둘째 자리 표기로 보낸다.
    protected Map<String, Object> notice(LinkedJob job, long revision, boolean funded) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", job.orderId());
        body.put("jobVersion", job.paymentJobVersion());
        body.put("ownerMemberId", job.ownerMemberId());
        body.put("amount", BigDecimal.valueOf(job.amount()).setScale(2));
        body.put("currency", "KRW");
        body.put("fundingRevision", revision);
        body.put("funded", funded);
        return body;
    }

    protected Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(body);
        changed.put(field, value);
        return changed;
    }

    protected ResultActions sendFunding(Long jobPostId, String key, Map<String, Object> body) throws Exception {
        return sendFundingJson(jobPostId, key, OBJECT_MAPPER.writeValueAsString(body));
    }

    protected ResultActions sendFundingJson(Long jobPostId, String key, String json) throws Exception {
        return mockMvc.perform(post("/api/jobs/internal/{jobPostId}/funding-status", jobPostId)
                .header("X-Internal-Secret", INTERNAL_SECRET)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    // payment-service가 쓰는 키 형식: funding-{orderId}-{fundingRevision}
    protected String fundingKey(LinkedJob job, long revision) {
        return "funding-" + job.orderId() + "-" + revision;
    }

    protected ResultActions requestAdmission(Long jobPostId, String key, long workerMemberId) throws Exception {
        return mockMvc.perform(post("/api/jobs/internal/{jobPostId}/application-admissions", jobPostId)
                .header("X-Internal-Secret", INTERNAL_SECRET)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workerMemberId\":" + workerMemberId + "}"));
    }

    protected ResultActions reserveSeat(Long jobPostId, String key) throws Exception {
        long id = EXTERNAL_ID_SEQUENCE.incrementAndGet();
        return reserveSeat(jobPostId, key, id, id, id);
    }

    protected ResultActions reserveSeat(Long jobPostId, String key, long matchingId, long applicationId, long workerId)
            throws Exception {
        return mockMvc.perform(post("/api/jobs/internal/{jobPostId}/matching-seat-reservations", jobPostId)
                .header("X-Internal-Secret", INTERNAL_SECRET)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"matchingId\":%d,\"applicationId\":%d,\"workerMemberId\":%d}"
                        .formatted(matchingId, applicationId, workerId)));
    }

    protected ResultActions seatCommand(Long jobPostId, String reservationId, String command, String key)
            throws Exception {
        return mockMvc.perform(post("/api/jobs/internal/{jobPostId}/matching-seat-reservations/{reservationId}/"
                        + command, jobPostId, reservationId)
                .header("X-Internal-Secret", INTERNAL_SECRET)
                .header("Idempotency-Key", key));
    }

    protected String reservationId(ResultActions result) throws Exception {
        String content = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(content, "$.data.reservationId");
    }

    protected JobPost jobPost(Long jobPostId) {
        return jobPostRepository.findById(jobPostId).orElseThrow();
    }

    protected String newKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
