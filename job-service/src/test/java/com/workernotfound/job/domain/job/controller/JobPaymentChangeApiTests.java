package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentChangeStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus;
import com.workernotfound.job.support.PaymentChangeTestSupport;
import com.workernotfound.job.support.StubPaymentServer.RecordedRequest;
import com.workernotfound.job.support.TestAccessTokens;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 점주의 결제 조건 변경·재결제 API. 권한, 입력 검증, 요청 멱등성, 새 결제 스냅샷·명령 발급, 같은 조건 재결제의 새 시도 식별을 검증한다.
 * 주문 교체 결과(거절·결과 불명·응답 유실)는 {@code JobPaymentReplacementTests}가 다룬다.
 */
class JobPaymentChangeApiTests extends PaymentChangeTestSupport {

    // 모레 09:00~13:00(240분), 시급 10,320원이면 1인 41,280원
    private static final long WAGE_PER_WORKER = 41_280L;

    @Test
    void onlyAuthenticatedOwnerOfJobCanChangeOrRetry() throws Exception {
        LinkedJob job = createLinkedJob(1);
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(terms(job, 2));

        mockMvc.perform(put("/api/jobs/{id}/payment-terms", job.jobPostId())
                        .header("Idempotency-Key", newKey("change"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/jobs/{id}/payment-terms", job.jobPostId())
                        .header("Authorization", TestAccessTokens.bearer(
                                TestAccessTokens.worker(jwtProperties.secret(), job.ownerMemberId())))
                        .header("Idempotency-Key", newKey("change"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/jobs/{id}/payment-order/retries", job.jobPostId())
                        .header("Idempotency-Key", newKey("retry")))
                .andExpect(status().isUnauthorized());
        // 다른 점주에게는 공고의 존재를 드러내지 않는다. 본문의 ownerMemberId는 권한 판단에 쓰지 않는다.
        Map<String, Object> claimingOwner = with(terms(job, 2), "ownerMemberId", job.ownerMemberId());
        changeTerms(job.jobPostId(), job.ownerMemberId() + 1, newKey("change"), claimingOwner)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB-404-001"));
        retryPayment(job.jobPostId(), job.ownerMemberId() + 1, newKey("retry"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB-404-001"));
        changeTerms(Long.MAX_VALUE, job.ownerMemberId(), newKey("change"), terms(job, 2))
                .andExpect(status().isNotFound());

        assertNoChangeIssued(job);
    }

    @Test
    void validatesTimesWagesRecruitCountDeadlineAndKey() throws Exception {
        LinkedJob job = createLinkedJob(1);
        long owner = job.ownerMemberId();

        changeTerms(job.jobPostId(), owner, newKey("c"), with(terms(job, 1), "endTime", "08:00:00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("JOB-400-001"));
        changeTerms(job.jobPostId(), owner, newKey("c"), with(terms(job, 1), "startTime", "09:00:30"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("JOB-400-001"));
        // 익일 플래그로 24시간을 넘는 구간
        changeTerms(job.jobPostId(), owner, newKey("c"), with(with(terms(job, 1), "isEndTimeNextDay", true),
                "endTime", "09:01:00"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("JOB-400-001"));
        changeTerms(job.jobPostId(), owner, newKey("c"), with(terms(job, 1), "baseHourlyWage", 10_319))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GLOBAL-400-002"));
        changeTerms(job.jobPostId(), owner, newKey("c"), with(terms(job, 1), "extraWage", -1))
                .andExpect(status().isBadRequest());
        changeTerms(job.jobPostId(), owner, newKey("c"), with(terms(job, 1), "recruitCount", 0))
                .andExpect(status().isBadRequest());
        // 1인 금액 × 모집 인원이 payment-service 금액 상한을 넘거나 long 곱셈이 넘치면 거절한다.
        changeTerms(job.jobPostId(), owner, newKey("c"), with(with(terms(job, 1), "baseHourlyWage", Integer.MAX_VALUE),
                "recruitCount", Integer.MAX_VALUE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("JOB-400-004"));
        String workStart = jobPost(job.jobPostId()).getWorkDate().atTime(LocalTime.of(9, 0)).toString();
        changeTerms(job.jobPostId(), owner, newKey("c"), with(terms(job, 1), "applicationDeadline", workStart))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("JOB-400-002"));
        changeTerms(job.jobPostId(), owner, newKey("c"),
                with(terms(job, 1), "applicationDeadline", now().minusMinutes(1).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("JOB-400-002"));
        changeTerms(job.jobPostId(), owner, newKey("c"), with(with(terms(job, 1),
                "workDate", LocalDate.now().minusDays(1).toString()),
                "applicationDeadline", LocalDate.now().minusDays(2).atTime(LocalTime.NOON).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("JOB-400-002"));
        changeTerms(job.jobPostId(), owner, " bad key", terms(job, 2)).andExpect(status().isBadRequest());
        changeTerms(job.jobPostId(), owner, "k".repeat(101), terms(job, 2)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/jobs/{id}/payment-order/retries", job.jobPostId())
                        .header("Authorization", TestAccessTokens.bearer(TestAccessTokens.owner(jwtProperties.secret(), owner))))
                .andExpect(status().isBadRequest());
        // 지금과 같은 조건은 변경이 아니다. 같은 조건의 새 결제는 재결제 요청을 쓴다.
        changeTerms(job.jobPostId(), owner, newKey("c"), terms(job, 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("JOB-400-005"));

        assertNoChangeIssued(job);
    }

    @Test
    void termsChangeStoresPendingSnapshotAndNewCommandWithoutTouchingCurrentTerms() throws Exception {
        LinkedJob job = createLinkedJob(1);
        JobPost before = jobPost(job.jobPostId());
        String key = newKey("change");

        Long changeId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), key, terms(job, 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changeType").value("TERMS_CHANGE"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.paymentJobVersion").value(job.paymentJobVersion() + 1))
                .andExpect(jsonPath("$.data.amount").value(WAGE_PER_WORKER * 3))
                .andExpect(jsonPath("$.data.paymentOrderId").doesNotExist()));

        JobPaymentOrderCommand first = commandRepository.findByJobPostIdOrderByIssueSequenceAsc(job.jobPostId()).get(0);
        JobPaymentOrderCommand next = commandOf(changeId);
        assertThat(next.getIssueSequence()).isEqualTo(2);
        assertThat(next.getStatus()).isEqualTo(PaymentOrderCommandStatus.PENDING);
        assertThat(next.getIdempotencyKey()).isNotEqualTo(first.getIdempotencyKey()).isNotEqualTo(key);
        assertThat(next.getOwnerMemberId()).isEqualTo(job.ownerMemberId());
        assertThat(next.getCurrency()).isEqualTo("KRW");
        assertThat(changeRequest(changeId).terms().recruitCount()).isEqualTo(3);
        // 주문 교체가 확인되기 전에는 현재 조건과 주문 연결을 바꾸지 않는다.
        JobPost pending = jobPost(job.jobPostId());
        assertThat(pending.getRecruitCount()).isEqualTo(1);
        assertThat(pending.getPaymentOrderId()).isEqualTo(job.orderId());
        assertThat(pending.getPaymentAmount()).isEqualTo(job.amount());
        assertThat(pending.getVersion()).isEqualTo(before.getVersion());
        queryPaymentOrder(job.jobPostId(), job.ownerMemberId())
                .andExpect(jsonPath("$.data.paymentOrderId").value(job.orderId()))
                .andExpect(jsonPath("$.data.latestChange.changeId").value(changeId))
                .andExpect(jsonPath("$.data.latestChange.status").value("PENDING"));

        LinkedJob replaced = dispatchChange(changeId);

        RecordedRequest sent = lastRequest();
        assertThat(sent.header("Idempotency-Key")).isEqualTo(next.getIdempotencyKey());
        assertThat(sent.jsonBody().path("jobVersion").longValue()).isEqualTo(job.paymentJobVersion() + 1);
        assertThat(sent.jsonBody().path("amount").longValue()).isEqualTo(WAGE_PER_WORKER * 3);
        JobPost applied = jobPost(job.jobPostId());
        assertThat(applied.getRecruitCount()).isEqualTo(3);
        assertThat(applied.getPaymentOrderId()).isEqualTo(replaced.orderId()).isNotEqualTo(job.orderId());
        assertThat(applied.getPaymentAmount()).isEqualTo(WAGE_PER_WORKER * 3);
        // 결제용 버전은 명령 발급 당시 값이며, 연결로 증가한 @Version과 다르다.
        assertThat(applied.getPaymentJobVersion()).isEqualTo(next.getJobVersion());
        assertThat(applied.getVersion()).isGreaterThan(before.getVersion());
        // 새 주문 연결은 공개나 차단 해제가 아니다. 새 주문의 예치 확인을 기다린다.
        assertThat(applied.getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        assertThat(applied.isFundingBlocked()).isFalse();
        assertThat(changeRequest(changeId).getStatus()).isEqualTo(PaymentChangeStatus.APPLIED);
        assertThat(commandRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderCommandStatus.SUCCEEDED);
        queryPaymentOrder(job.jobPostId(), job.ownerMemberId())
                .andExpect(jsonPath("$.data.paymentOrderId").value(replaced.orderId()))
                .andExpect(jsonPath("$.data.latestChange.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.latestChange.paymentOrderId").value(replaced.orderId()));
    }

    @Test
    void sameKeyReturnsSameRequestAndProgressWhileDifferentRequestConflicts() throws Exception {
        LinkedJob job = createLinkedJob(1);
        String key = newKey("change");
        Long changeId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), key, terms(job, 2)));

        // 진행 중에는 같은 요청 상태를, 처리 후에는 처리 결과를 돌려준다. 새 명령을 발급하지 않는다.
        changeTerms(job.jobPostId(), job.ownerMemberId(), key, terms(job, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changeId").value(changeId))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        LinkedJob replaced = dispatchChange(changeId);
        changeTerms(job.jobPostId(), job.ownerMemberId(), key, terms(job, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changeId").value(changeId))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.paymentOrderId").value(replaced.orderId()));
        // 공고가 바뀐 뒤에도 같은 키는 처음 요청에 묶인다.
        changeTerms(job.jobPostId(), job.ownerMemberId(), key, terms(job, 4))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-004"));
        retryPayment(job.jobPostId(), job.ownerMemberId(), key)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-004"));
        LinkedJob other = createLinkedJob(1);
        changeTerms(other.jobPostId(), other.ownerMemberId(), key, terms(other, 2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-004"));

        assertThat(commandRepository.findByJobPostIdOrderByIssueSequenceAsc(job.jobPostId())).hasSize(2);
        assertNoChangeIssued(other);
    }

    // 시각에 따라 달라지는 검증은 새 요청에만 적용한다. 마감·근무일이 지난 뒤의 같은 키 재요청도 처음 처리 상태를 돌려준다.
    @Test
    void sameKeyReplayAfterDeadlineAndWorkDatePassStillReturnsOriginalRequest() throws Exception {
        LinkedJob job = createLinkedJob(1);
        String key = newKey("change");
        LocalDateTime deadline = LocalDateTime.now().plusSeconds(2);
        Map<String, Object> body = with(terms(job, 2), "applicationDeadline", deadline.toString());
        Long changeId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), key, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING")));

        // 실제 시스템 시각으로 마감을 지나게 한다. 요청 경계의 시각 검증이 재요청을 막지 않는지 확인한다.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> LocalDateTime.now().isAfter(deadline));
        changeTerms(job.jobPostId(), job.ownerMemberId(), key, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changeId").value(changeId))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        dispatchChange(changeId);
        clock.fixAt(jobPost(job.jobPostId()).getWorkDate().plusDays(3).atStartOfDay(ZoneId.systemDefault()).toInstant());
        changeTerms(job.jobPostId(), job.ownerMemberId(), key, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changeId").value(changeId))
                .andExpect(jsonPath("$.data.status").value("APPLIED"));
        // 같은 본문이라도 새 키는 새 요청이므로 지금 시각 기준으로 검증한다.
        changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("change"), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("JOB-400-002"));
        assertThat(commandRepository.findByJobPostIdOrderByIssueSequenceAsc(job.jobPostId())).hasSize(2);
    }

    @Test
    void repaymentOfFailedOrderKeepsVersionAndAmountButIssuesNewAttemptKey() throws Exception {
        LinkedJob job = createLinkedJob(2);
        String key = newKey("retry");

        Long changeId = changeId(retryPayment(job.jobPostId(), job.ownerMemberId(), key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changeType").value("REPAYMENT"))
                .andExpect(jsonPath("$.data.paymentJobVersion").value(job.paymentJobVersion()))
                .andExpect(jsonPath("$.data.amount").value(job.amount())));
        retryPayment(job.jobPostId(), job.ownerMemberId(), key)
                .andExpect(jsonPath("$.data.changeId").value(changeId));

        List<JobPaymentOrderCommand> commands = commandRepository.findByJobPostIdOrderByIssueSequenceAsc(job.jobPostId());
        assertThat(commands).hasSize(2);
        JobPaymentOrderCommand retry = commands.get(1);
        assertThat(retry.getJobVersion()).isEqualTo(commands.get(0).getJobVersion());
        assertThat(retry.getAmount()).isEqualTo(commands.get(0).getAmount());
        // 같은 금액이어도 새 결제 시도이므로 새 주문 생성 키다.
        assertThat(retry.getIdempotencyKey()).isNotEqualTo(commands.get(0).getIdempotencyKey());

        LinkedJob repaid = dispatchChange(changeId);
        assertThat(repaid.orderId()).isNotEqualTo(job.orderId());
        JobPost linked = jobPost(job.jobPostId());
        assertThat(linked.getPaymentOrderId()).isEqualTo(repaid.orderId());
        assertThat(linked.getPaymentJobVersion()).isEqualTo(job.paymentJobVersion());
        assertThat(linked.getRecruitCount()).isEqualTo(2);
        assertThat(changeRequest(changeId).getStatus()).isEqualTo(PaymentChangeStatus.APPLIED);
        assertThat(PAYMENT_SERVER.issuedOrderCount()).isEqualTo(2);
    }

    @Test
    void rejectsSecondReplacementWhileOrderCreationOutcomeIsUnknown() throws Exception {
        LinkedJob job = createLinkedJob(1);
        Long changeId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 2)));

        changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 3))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-015"));
        retryPayment(job.jobPostId(), job.ownerMemberId(), newKey("r"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-015"));
        // 최초 주문 생성이 아직 확인되지 않은 공고도 같다.
        JobPaymentOrderCommand initial = createPendingJob(1);
        retryPayment(initial.getJobPostId(), initial.getOwnerMemberId(), newKey("r"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-015"));

        dispatchChange(changeId);
        changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 3)).andExpect(status().isOk());
    }

    @Test
    void rejectsPublishedClosedBlockedAndFundedJobs() throws Exception {
        LinkedJob published = createLinkedJob(1);
        sendFunding(published.jobPostId(), fundingKey(published, 1), notice(published, 1, true))
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));
        changeTerms(published.jobPostId(), published.ownerMemberId(), newKey("c"), terms(published, 2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-014"));
        retryPayment(published.jobPostId(), published.ownerMemberId(), newKey("r"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-014"));

        // REVIEW_REQUIRED(funded=false)가 반영된 주문은 새 주문 발급으로 우회하지 않는다.
        LinkedJob blocked = createLinkedJob(1);
        sendFunding(blocked.jobPostId(), fundingKey(blocked, 1), notice(blocked, 1, false))
                .andExpect(jsonPath("$.data.result").value("FUNDING_BLOCKED"));
        retryPayment(blocked.jobPostId(), blocked.ownerMemberId(), newKey("r"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-014"));
        changeTerms(blocked.jobPostId(), blocked.ownerMemberId(), newKey("c"), terms(blocked, 2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-014"));

        for (LinkedJob job : List.of(published, blocked)) {
            assertNoChangeIssued(job);
        }
    }

    @Test
    void repaymentAfterDeadlineIsRejectedButScheduleCanBeChanged() throws Exception {
        LinkedJob job = createLinkedJob(1);
        JobPost jobPost = jobPost(job.jobPostId());
        clock.fixAt(jobPost.getApplicationDeadline().atZone(java.time.ZoneId.systemDefault()).toInstant());

        retryPayment(job.jobPostId(), job.ownerMemberId(), newKey("r"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-014"));
        assertNoChangeIssued(job);

        Map<String, Object> later = with(terms(job, 1), "applicationDeadline",
                jobPost.getWorkDate().atTime(LocalTime.of(8, 0)).toString());
        changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), later)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    private RecordedRequest lastRequest() {
        List<RecordedRequest> requests = PAYMENT_SERVER.requests();
        return requests.get(requests.size() - 1);
    }

    private void assertNoChangeIssued(LinkedJob job) {
        assertThat(commandRepository.findByJobPostIdOrderByIssueSequenceAsc(job.jobPostId())).hasSize(1);
        assertThat(changeRequestRepository.findFirstByJobPostIdOrderByIdDesc(job.jobPostId())).isEmpty();
        JobPost jobPost = jobPost(job.jobPostId());
        assertThat(jobPost.getPaymentOrderId()).isEqualTo(job.orderId());
    }
}
