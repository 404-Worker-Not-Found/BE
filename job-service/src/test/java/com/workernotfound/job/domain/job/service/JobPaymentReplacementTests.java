package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPaymentRefundReview;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentChangeStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderFailureType;
import com.workernotfound.job.domain.job.entity.enums.RefundReviewReason;
import com.workernotfound.job.domain.job.exception.PaymentOrderCreationException;
import com.workernotfound.job.support.PaymentChangeTestSupport;
import com.workernotfound.job.support.StubPaymentServer.RecordedRequest;
import com.workernotfound.job.support.StubPaymentServer.StubResponse;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 결제 조건 변경·재결제 명령의 주문 교체 결과와 예치 알림의 일관성.
 *
 * <p>payment-service 대역의 409는 payment-service가 이전 주문(CONFIRMING·DEPOSITED·REVIEW_REQUIRED, 같은 버전의 READY)의
 * 교체를 거절한 응답이다. 상태별 거절 판단은 payment-service의 {@code OrderReplacementTests}가 실제 주문 상태로 검증한다.
 */
class JobPaymentReplacementTests extends PaymentChangeTestSupport {

    private static final long WAGE_PER_WORKER = 41_280L;

    @Autowired
    private PaymentOrderCommandTransactionService transactionService;

    @Test
    void definitiveRejectionKeepsCurrentTermsAndOrderAndAllowsNewAttemptLater() throws Exception {
        LinkedJob job = createLinkedJob(1);
        JobPost before = jobPost(job.jobPostId());
        Long rejectedId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 2)));

        PAYMENT_SERVER.enqueue(StubResponse.error(409, "ORDER-409-001"));
        assertThat(paymentOrderDispatcher.dispatch(commandOf(rejectedId).getId())).isTrue();

        JobPaymentOrderCommand rejected = commandOf(rejectedId);
        assertThat(rejected.getStatus()).isEqualTo(PaymentOrderCommandStatus.REJECTED);
        assertThat(rejected.getLastFailureType()).isEqualTo(PaymentOrderFailureType.CONFLICT);
        assertThat(rejected.getLastFailureCode()).isEqualTo("ORDER-409-001");
        assertThat(rejected.getOrderId()).isNull();
        assertThat(changeRequest(rejectedId).getStatus()).isEqualTo(PaymentChangeStatus.REJECTED);
        assertThat(changeRequest(rejectedId).getResolutionCode()).isEqualTo("ORDER-409-001");
        JobPost after = jobPost(job.jobPostId());
        assertThat(after.paymentTerms()).isEqualTo(before.paymentTerms());
        assertThat(after.getPaymentOrderId()).isEqualTo(job.orderId());
        assertThat(after.getPaymentJobVersion()).isEqualTo(job.paymentJobVersion());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        // 거절된 명령은 종료 상태라 실행권을 다시 얻지 못하므로 같은 키를 다시 보내지 않는다.
        advancePastRetry();
        assertThat(transactionService.claim(rejected.getId(), UUID.randomUUID().toString(), now(), now().plusSeconds(3)))
                .isEmpty();
        clock.reset();

        // 결과가 확인된 뒤 새 요청은 새 키와 더 큰 결제용 버전으로 다시 시도한다.
        Long nextId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentJobVersion").value(job.paymentJobVersion() + 2)));
        assertThat(commandOf(nextId).getIdempotencyKey()).isNotEqualTo(rejected.getIdempotencyKey());
        LinkedJob replaced = dispatchChange(nextId);
        assertThat(jobPost(job.jobPostId()).getPaymentOrderId()).isEqualTo(replaced.orderId());
        assertThat(jobPost(job.jobPostId()).getRecruitCount()).isEqualTo(2);
    }

    @Test
    void rejectedRepaymentKeepsCurrentOrder() throws Exception {
        LinkedJob job = createLinkedJob(1);
        Long changeId = changeId(retryPayment(job.jobPostId(), job.ownerMemberId(), newKey("r")));

        // 같은 버전의 READY, CONFIRMING 등 FAILED가 아닌 주문은 payment-service가 같은 조건으로 교체하지 않는다.
        PAYMENT_SERVER.enqueue(StubResponse.error(409, "ORDER-409-001"));
        paymentOrderDispatcher.dispatch(commandOf(changeId).getId());

        assertThat(changeRequest(changeId).getStatus()).isEqualTo(PaymentChangeStatus.REJECTED);
        assertThat(jobPost(job.jobPostId()).getPaymentOrderId()).isEqualTo(job.orderId());
        retryPayment(job.jobPostId(), job.ownerMemberId(), changeRequest(changeId).getIdempotencyKey())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.resolutionCode").value("ORDER-409-001"))
                .andExpect(jsonPath("$.data.paymentOrderId").doesNotExist());
    }

    @Test
    void unknownOutcomesStayPendingAndConvergeWithSameKeyAndSnapshot() throws Exception {
        LinkedJob job = createLinkedJob(1);
        JobPost before = jobPost(job.jobPostId());
        Long changeId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 3)));
        JobPaymentOrderCommand command = commandOf(changeId);

        // payment-service는 주문을 만들었지만 응답이 유실된다. 이어서 5xx와 잘못된 응답이 온다.
        PAYMENT_SERVER.enqueueProcessedButDelayed(CALL_TIMEOUT.multipliedBy(2));
        PAYMENT_SERVER.enqueue(StubResponse.error(503, "GLOBAL-503-001"), new StubResponse(200, "{\"success\":tr"));
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(paymentOrderDispatcher.dispatch(command.getId())).isTrue();
            assertThat(commandOf(changeId).getStatus()).isEqualTo(PaymentOrderCommandStatus.PENDING);
            assertThat(changeRequest(changeId).getStatus()).isEqualTo(PaymentChangeStatus.PENDING);
            // 결과 불명 동안 기존 조건으로 되돌리거나 새 조건을 적용하지 않는다.
            assertThat(jobPost(job.jobPostId()).paymentTerms()).isEqualTo(before.paymentTerms());
            assertThat(jobPost(job.jobPostId()).getPaymentOrderId()).isEqualTo(job.orderId());
            advancePastRetry();
        }
        String createdOrderId = PAYMENT_SERVER.issuedOrderId(command.getIdempotencyKey());

        assertThat(paymentOrderDispatcher.dispatch(command.getId())).isTrue();

        assertThat(jobPost(job.jobPostId()).getPaymentOrderId()).isEqualTo(createdOrderId);
        assertThat(jobPost(job.jobPostId()).getRecruitCount()).isEqualTo(3);
        assertThat(changeRequest(changeId).getStatus()).isEqualTo(PaymentChangeStatus.APPLIED);
        List<RecordedRequest> sent = PAYMENT_SERVER.requests().subList(1, PAYMENT_SERVER.requests().size());
        assertThat(sent).hasSize(4).allSatisfy(request -> {
            assertThat(request.header("Idempotency-Key")).isEqualTo(command.getIdempotencyKey());
            assertThat(request.body()).isEqualTo(sent.get(0).body());
        });
        assertThat(PAYMENT_SERVER.issuedOrderCount()).isEqualTo(2);
    }

    @Test
    void newOrderFundingBeforeLinkIsRetriedNotTreatedAsEarlierOrder() throws Exception {
        LinkedJob job = createLinkedJob(1);
        // 같은 조건 재결제라 새 주문의 결제 스냅샷이 이전 주문과 같다. 주문 ID로만 구분된다.
        Long changeId = changeId(retryPayment(job.jobPostId(), job.ownerMemberId(), newKey("r")));
        JobPaymentOrderCommand command = commandOf(changeId);
        PAYMENT_SERVER.enqueueProcessedButDelayed(CALL_TIMEOUT.multipliedBy(2));
        paymentOrderDispatcher.dispatch(command.getId());
        LinkedJob created = issuedJob(command);
        assertThat(created.orderId()).isNotNull().isNotEqualTo(job.orderId());

        for (int attempt = 0; attempt < 2; attempt++) {
            sendFunding(job.jobPostId(), fundingKey(created, 1), notice(created, 1, true))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("JOB-409-013"));
        }
        assertThat(receiptRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).isEmpty();
        assertThat(jobPost(job.jobPostId()).getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);

        advancePastRetry();
        paymentOrderDispatcher.dispatch(command.getId());
        clock.reset();

        sendFunding(job.jobPostId(), fundingKey(created, 1), notice(created, 1, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));
        assertThat(jobPost(job.jobPostId()).getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(refundReviewRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).isEmpty();
    }

    @Test
    void termsChangeFundingPublishesWithNewTermsOnlyAfterLink() throws Exception {
        LinkedJob job = createLinkedJob(1);
        Long changeId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 2)));
        JobPaymentOrderCommand command = commandOf(changeId);
        PAYMENT_SERVER.enqueueProcessedButDelayed(CALL_TIMEOUT.multipliedBy(2));
        paymentOrderDispatcher.dispatch(command.getId());
        LinkedJob created = issuedJob(command);

        sendFunding(job.jobPostId(), fundingKey(created, 1), notice(created, 1, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-013"));

        advancePastRetry();
        paymentOrderDispatcher.dispatch(command.getId());
        clock.reset();
        sendFunding(job.jobPostId(), fundingKey(created, 1), notice(created, 1, true))
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));

        JobPost published = jobPost(job.jobPostId());
        assertThat(published.getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(published.getRecruitCount()).isEqualTo(2);
        assertThat(published.getPaymentAmount()).isEqualTo(WAGE_PER_WORKER * 2);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).hasSize(1);
    }

    @Test
    void linkFailureRollsBackCommandOrderAndTermsAndRetryConverges() throws Exception {
        LinkedJob job = createLinkedJob(1);
        JobPost before = jobPost(job.jobPostId());
        Long changeId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 2)));
        JobPaymentOrderCommand command = commandOf(changeId);

        String trigger = failOn("BEFORE UPDATE", "job_payment_change_requests", job.jobPostId());
        try {
            // 응답은 받았지만 결과 저장 트랜잭션이 실패한다.
            assertThatThrownBy(() -> paymentOrderDispatcher.dispatch(command.getId()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            dropTrigger(trigger);
        }
        assertThat(commandOf(changeId).getStatus()).isEqualTo(PaymentOrderCommandStatus.PENDING);
        assertThat(commandOf(changeId).getOrderId()).isNull();
        assertThat(changeRequest(changeId).getStatus()).isEqualTo(PaymentChangeStatus.PENDING);
        JobPost rolledBack = jobPost(job.jobPostId());
        assertThat(rolledBack.paymentTerms()).isEqualTo(before.paymentTerms());
        assertThat(rolledBack.getPaymentOrderId()).isEqualTo(job.orderId());
        assertThat(rolledBack.getVersion()).isEqualTo(before.getVersion());
        String createdOrderId = PAYMENT_SERVER.issuedOrderId(command.getIdempotencyKey());

        // 실행권이 만료되면 같은 키로 다시 보내 원래 주문을 연결한다.
        advancePastRetry();
        assertThat(paymentOrderDispatcher.dispatch(command.getId())).isTrue();
        assertThat(jobPost(job.jobPostId()).getPaymentOrderId()).isEqualTo(createdOrderId);
        assertThat(jobPost(job.jobPostId()).getRecruitCount()).isEqualTo(2);
        assertThat(PAYMENT_SERVER.issuedOrderCount()).isEqualTo(2);
    }

    @Test
    void unrecordedRejectionIsNotFinal() throws Exception {
        LinkedJob job = createLinkedJob(1);
        Long changeId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 2)));
        JobPaymentOrderCommand command = commandOf(changeId);

        PAYMENT_SERVER.enqueue(StubResponse.error(409, "ORDER-409-001"));
        String trigger = failOn("BEFORE UPDATE", "job_payment_change_requests", job.jobPostId());
        try {
            assertThatThrownBy(() -> paymentOrderDispatcher.dispatch(command.getId()))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            dropTrigger(trigger);
        }
        assertThat(commandOf(changeId).getStatus()).isEqualTo(PaymentOrderCommandStatus.PENDING);
        assertThat(changeRequest(changeId).getStatus()).isEqualTo(PaymentChangeStatus.PENDING);

        // 거절이 기록되지 않았으므로 같은 명령을 다시 보내고, 그 사이 교체가 가능해졌다면 그 결과를 따른다.
        advancePastRetry();
        paymentOrderDispatcher.dispatch(command.getId());
        assertThat(changeRequest(changeId).getStatus()).isEqualTo(PaymentChangeStatus.APPLIED);
    }

    @Test
    void lateResultsOfExpiredExecutorCannotOverwriteLatestState() throws Exception {
        LinkedJob job = createLinkedJob(1);
        Long changeId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 2)));
        JobPaymentOrderCommand command = commandOf(changeId);
        String staleToken = UUID.randomUUID().toString();
        assertThat(transactionService.claim(command.getId(), staleToken, now(), now().plusSeconds(3))).isPresent();

        advancePastRetry();
        LinkedJob replaced = dispatchChange(changeId);

        PaymentOrderCreationException conflict =
                new PaymentOrderCreationException(PaymentOrderFailureType.CONFLICT, 409, "ORDER-409-001");
        assertThat(transactionService.recordRejected(command.getId(), staleToken, conflict, now())).isFalse();
        assertThat(transactionService.recordCreated(command.getId(), staleToken, UUID.randomUUID().toString(), now()))
                .isEqualTo(PaymentOrderLinkResult.LEASE_LOST);
        assertThat(jobPost(job.jobPostId()).getPaymentOrderId()).isEqualTo(replaced.orderId());
        assertThat(changeRequest(changeId).getStatus()).isEqualTo(PaymentChangeStatus.APPLIED);
        assertThat(commandOf(changeId).getOrderId()).isEqualTo(replaced.orderId());
    }

    @Test
    void lateEarlierOrderNotificationsNeitherBlockNorPublishNewOrder() throws Exception {
        LinkedJob earlier = createLinkedJob(1);
        Long changeId = changeId(changeTerms(earlier.jobPostId(), earlier.ownerMemberId(), newKey("c"),
                terms(earlier, 2)));
        LinkedJob latest = dispatchChange(changeId);

        sendFunding(earlier.jobPostId(), fundingKey(earlier, 1), notice(earlier, 1, false))
                .andExpect(jsonPath("$.data.result").value("STALE_ORDER"))
                .andExpect(jsonPath("$.data.fundingBlocked").value(false));
        sendFunding(earlier.jobPostId(), fundingKey(earlier, 2), notice(earlier, 2, true))
                .andExpect(jsonPath("$.data.result").value("STALE_ORDER"))
                .andExpect(jsonPath("$.data.jobStatus").value("PAYMENT_PENDING"));
        assertThat(jobPost(latest.jobPostId()).getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);

        sendFunding(latest.jobPostId(), fundingKey(latest, 2), notice(latest, 2, true))
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));
        sendFunding(latest.jobPostId(), fundingKey(latest, 4), notice(latest, 4, false))
                .andExpect(jsonPath("$.data.result").value("FUNDING_BLOCKED"));
        // revision 역전·중복
        sendFunding(latest.jobPostId(), fundingKey(latest, 3), notice(latest, 3, true))
                .andExpect(jsonPath("$.data.result").value("STALE_REVISION"))
                .andExpect(jsonPath("$.data.fundingBlocked").value(true));
        sendFunding(latest.jobPostId(), fundingKey(latest, 4), notice(latest, 4, false))
                .andExpect(jsonPath("$.data.result").value("FUNDING_BLOCKED"));
        sendFunding(latest.jobPostId(), fundingKey(latest, 4), notice(latest, 4, true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-004"));
        // 이전 주문의 높은 revision 예치 확인도 최신 주문의 차단을 풀지 않는다.
        sendFunding(earlier.jobPostId(), fundingKey(earlier, 9), notice(earlier, 9, true))
                .andExpect(jsonPath("$.data.result").value("STALE_ORDER"))
                .andExpect(jsonPath("$.data.fundingBlocked").value(true));

        JobPost job = jobPost(latest.jobPostId());
        assertThat(job.getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(job.isFundingBlocked()).isTrue();
        assertThat(job.getPaymentOrderId()).isEqualTo(latest.orderId());
        assertThat(fundingRepository.findByOrderId(earlier.orderId())).isEmpty();
        assertThat(fundingRepository.findByOrderId(latest.orderId()).orElseThrow().getFundingRevision()).isEqualTo(4L);
        // 이전 주문의 예치 확인은 주문당 한 번만, 처음 확인한 수신 기록에 연결해 환불 검토 대상으로 남는다.
        List<JobPaymentRefundReview> reviews = refundReviewRepository.findByJobPostIdOrderByIdAsc(job.getId());
        assertThat(reviews).singleElement().satisfies(review -> {
            assertThat(review.getOrderId()).isEqualTo(earlier.orderId());
            assertThat(review.getReason()).isEqualTo(RefundReviewReason.STALE_ORDER);
            assertThat(review.getReceiptId()).isEqualTo(
                    receiptRepository.findByOrderIdAndFundingRevision(earlier.orderId(), 2L).orElseThrow().getId());
        });
    }

    @Test
    void blockFromCurrentOrderSurvivesRejectedOrUnexpectedReplacement() throws Exception {
        LinkedJob rejectedJob = createLinkedJob(1);
        Long rejectedId = changeId(changeTerms(rejectedJob.jobPostId(), rejectedJob.ownerMemberId(), newKey("c"),
                terms(rejectedJob, 2)));
        // 변경 대기 중에 현재 주문의 취소·검토 필요가 확인됐다. payment-service는 REVIEW_REQUIRED 주문을 교체하지 않는다.
        sendFunding(rejectedJob.jobPostId(), fundingKey(rejectedJob, 1), notice(rejectedJob, 1, false))
                .andExpect(jsonPath("$.data.result").value("FUNDING_BLOCKED"));
        PAYMENT_SERVER.enqueue(StubResponse.error(409, "ORDER-409-001"));
        paymentOrderDispatcher.dispatch(commandOf(rejectedId).getId());
        assertThat(changeRequest(rejectedId).getStatus()).isEqualTo(PaymentChangeStatus.REJECTED);
        assertThat(jobPost(rejectedJob.jobPostId()).isFundingBlocked()).isTrue();
        assertThat(jobPost(rejectedJob.jobPostId()).getPaymentOrderId()).isEqualTo(rejectedJob.orderId());

        // 정상 교체 정책에서는 생기지 않는 응답(교체 성공)이 와도 새 주문을 연결하거나 차단을 풀지 않는다.
        LinkedJob job = createLinkedJob(1);
        Long changeId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 2)));
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, false))
                .andExpect(jsonPath("$.data.result").value("FUNDING_BLOCKED"));
        LinkedJob unexpected = dispatchChange(changeId);

        assertUnlinked(job, changeId, unexpected);
        assertThat(jobPost(job.jobPostId()).isFundingBlocked()).isTrue();
        // 연결하지 않은 주문의 늦은 예치 확인은 이 공고의 이전 주문으로 다뤄 환불 검토 대상만 남긴다.
        sendFunding(job.jobPostId(), fundingKey(unexpected, 1), notice(unexpected, 1, true))
                .andExpect(jsonPath("$.data.result").value("STALE_ORDER"))
                .andExpect(jsonPath("$.data.fundingBlocked").value(true))
                .andExpect(jsonPath("$.data.jobStatus").value("PAYMENT_PENDING"));
        assertThat(refundReviewRepository.findByOrderId(unexpected.orderId())).isPresent();
    }

    @Test
    void earlierOrderPublishedWhileChangePendingKeepsPublishedTerms() throws Exception {
        LinkedJob job = createLinkedJob(1);
        Long rejectedId = changeId(changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), terms(job, 2)));
        // 변경 요청 뒤 이전 주문의 결제가 먼저 완료됐다. payment-service는 DEPOSITED 주문을 교체하지 않는다.
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));
        PAYMENT_SERVER.enqueue(StubResponse.error(409, "ORDER-409-001"));
        paymentOrderDispatcher.dispatch(commandOf(rejectedId).getId());

        assertThat(changeRequest(rejectedId).getStatus()).isEqualTo(PaymentChangeStatus.REJECTED);
        JobPost published = jobPost(job.jobPostId());
        assertThat(published.getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(published.getRecruitCount()).isEqualTo(1);
        assertThat(published.getPaymentOrderId()).isEqualTo(job.orderId());

        LinkedJob other = createLinkedJob(1);
        Long changeId = changeId(changeTerms(other.jobPostId(), other.ownerMemberId(), newKey("c"), terms(other, 2)));
        sendFunding(other.jobPostId(), fundingKey(other, 1), notice(other, 1, true))
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));
        LinkedJob unexpected = dispatchChange(changeId);
        assertUnlinked(other, changeId, unexpected);
        assertThat(jobPost(other.jobPostId()).getStatus()).isEqualTo(JobStatus.OPEN);
    }

    @Test
    void fundedButUnpublishedEarlierOrderStaysUnderReviewAndIsNotReplaced() throws Exception {
        LinkedJob job = createLinkedJob(1);
        JobPost jobPost = jobPost(job.jobPostId());
        clock.fixAt(jobPost.getApplicationDeadline().atZone(ZoneId.systemDefault()).toInstant());
        // 예치가 지원 마감 뒤에 확인돼 공개되지 않았다. 이 예치 사실은 교체 전에 먼저 검토 대상으로 기록된다.
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(jsonPath("$.data.result").value("PUBLICATION_SKIPPED"));

        Map<String, Object> later = with(terms(job, 1), "applicationDeadline",
                jobPost.getWorkDate().atTime(LocalTime.of(8, 0)).toString());
        changeTerms(job.jobPostId(), job.ownerMemberId(), newKey("c"), later)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB-409-014"));

        assertThat(refundReviewRepository.findByOrderId(job.orderId())).hasValueSatisfying(review ->
                assertThat(review.getReason()).isEqualTo(RefundReviewReason.APPLICATION_DEADLINE_PASSED));
        assertThat(commandRepository.findByJobPostIdOrderByIssueSequenceAsc(job.jobPostId())).hasSize(1);
    }

    @Test
    void changeRequestSaveFailureRollsBackIssuedCommand() throws Exception {
        LinkedJob job = createLinkedJob(1);
        String key = newKey("c");
        String trigger = failOn("BEFORE INSERT", "job_payment_change_requests", job.jobPostId());
        try {
            changeTerms(job.jobPostId(), job.ownerMemberId(), key, terms(job, 2))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("GLOBAL-500-001"));
        } finally {
            dropTrigger(trigger);
        }
        assertThat(commandRepository.findByJobPostIdOrderByIssueSequenceAsc(job.jobPostId())).hasSize(1);

        changeTerms(job.jobPostId(), job.ownerMemberId(), key, terms(job, 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    // 만들어졌지만 연결하지 않은 주문. 공고는 이전 주문과 이전 조건을 유지하고, 주문 ID는 명령에만 남는다.
    private void assertUnlinked(LinkedJob job, Long changeId, LinkedJob unexpected) {
        JobPaymentOrderCommand command = commandOf(changeId);
        assertThat(command.getStatus()).isEqualTo(PaymentOrderCommandStatus.SUPERSEDED);
        assertThat(command.getOrderId()).isEqualTo(unexpected.orderId());
        assertThat(changeRequest(changeId).getStatus()).isEqualTo(PaymentChangeStatus.REJECTED);
        assertThat(changeRequest(changeId).getResolutionCode()).isEqualTo("JOB_STATE_CHANGED");
        JobPost jobPost = jobPost(job.jobPostId());
        assertThat(jobPost.getPaymentOrderId()).isEqualTo(job.orderId());
        assertThat(jobPost.getRecruitCount()).isEqualTo(1);
    }
}
