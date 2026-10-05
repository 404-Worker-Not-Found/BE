package com.workernotfound.job.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.workernotfound.job.domain.job.entity.JobPaymentChangeRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.repository.JobPaymentChangeRequestRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentRefundReviewRepository;
import com.workernotfound.job.global.security.JwtProperties;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 결제 조건 변경·재결제 테스트의 공통 경로.
 *
 * <p>공고는 실제 등록·주문 생성·연결 경로로 만들고, 점주 API는 실제 JWT 필터를 통과하는 테스트 토큰으로 호출한다. 주문 교체 결과는
 * 실제 소켓의 payment-service 대역이 돌려준다. 대역은 payment-service의 주문 상태(READY·CONFIRMING 등)를 모르므로 교체 거절은
 * payment-service가 보내는 409 응답으로 재현하고, 상태별 거절 판단 자체는 payment-service 테스트가 검증한다.
 */
public abstract class PaymentChangeTestSupport extends FundingStatusApiTestSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    protected JwtProperties jwtProperties;

    @Autowired
    protected JobPaymentChangeRequestRepository changeRequestRepository;

    @Autowired
    protected JobPaymentRefundReviewRepository refundReviewRepository;

    // createLinkedJob과 같은 일정(모레 09:00~13:00, 시급 10,320원)에서 모집 인원만 바꾼 조건
    protected Map<String, Object> terms(LinkedJob job, int recruitCount) {
        LocalDate workDate = jobPost(job.jobPostId()).getWorkDate();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workDate", workDate.toString());
        body.put("startTime", "09:00:00");
        body.put("endTime", "13:00:00");
        body.put("isEndTimeNextDay", false);
        body.put("baseHourlyWage", 10_320);
        body.put("extraWage", null);
        body.put("recruitCount", recruitCount);
        body.put("applicationDeadline", workDate.minusDays(1).atTime(LocalTime.NOON).toString());
        return body;
    }

    protected ResultActions changeTerms(Long jobPostId, long ownerId, String key, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(put("/api/jobs/{id}/payment-terms", jobPostId)
                .header("Authorization", TestAccessTokens.bearer(TestAccessTokens.owner(jwtProperties.secret(), ownerId)))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(OBJECT_MAPPER.writeValueAsString(body)));
    }

    protected ResultActions retryPayment(Long jobPostId, long ownerId, String key) throws Exception {
        return mockMvc.perform(post("/api/jobs/{id}/payment-order/retries", jobPostId)
                .header("Authorization", TestAccessTokens.bearer(TestAccessTokens.owner(jwtProperties.secret(), ownerId)))
                .header("Idempotency-Key", key));
    }

    protected ResultActions queryPaymentOrder(Long jobPostId, long ownerId) throws Exception {
        return mockMvc.perform(get("/api/jobs/{id}/payment-order", jobPostId)
                .header("Authorization", TestAccessTokens.bearer(TestAccessTokens.owner(jwtProperties.secret(), ownerId))));
    }

    protected Long changeId(ResultActions result) throws Exception {
        String content = result.andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(content, "$.data.changeId")).longValue();
    }

    protected JobPaymentChangeRequest changeRequest(Long changeId) {
        return changeRequestRepository.findById(changeId).orElseThrow();
    }

    protected JobPaymentOrderCommand commandOf(Long changeId) {
        return commandRepository.findById(changeRequest(changeId).getCommandId()).orElseThrow();
    }

    // 결제 변경 명령을 payment-service 대역에 보내고, 대역이 만든 주문을 알림 대상 주문으로 돌려준다.
    protected LinkedJob dispatchChange(Long changeId) {
        JobPaymentOrderCommand command = commandOf(changeId);
        assertThat(paymentOrderDispatcher.dispatch(command.getId())).isTrue();
        return issuedJob(command);
    }

    protected LinkedJob issuedJob(JobPaymentOrderCommand command) {
        return new LinkedJob(command.getJobPostId(), PAYMENT_SERVER.issuedOrderId(command.getIdempotencyKey()),
                command.getJobVersion(), command.getOwnerMemberId(), command.getAmount());
    }

    // 실행권 만료와 backoff 대기를 지나 같은 명령을 다시 보낼 수 있는 시각으로 옮긴다.
    protected void advancePastRetry() {
        clock.fixAt(clock.instant().plus(BASE_DELAY.multipliedBy(4)).plusSeconds(60));
    }

    protected LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    protected void executeAsRoot(String sql) throws Exception {
        try (Connection connection = openLockObserverConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // 지정한 공고의 행 변경만 실패시키는 트리거. 다른 테스트의 공고에는 영향을 주지 않는다.
    protected String failOn(String timing, String table, Long jobPostId) throws Exception {
        String trigger = "fail_" + table + "_" + timing.toLowerCase().replace(' ', '_') + "_" + jobPostId;
        executeAsRoot("""
                CREATE TRIGGER %s %s ON %s FOR EACH ROW
                BEGIN
                    IF NEW.job_post_id = %d THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'blocked by test';
                    END IF;
                END
                """.formatted(trigger, timing, table, jobPostId));
        return trigger;
    }

    protected void dropTrigger(String trigger) throws Exception {
        executeAsRoot("DROP TRIGGER " + trigger);
    }
}
