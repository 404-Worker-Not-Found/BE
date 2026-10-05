package com.workernotfound.job.domain.job.controller;

import com.workernotfound.job.domain.job.entity.JobFundingStatusReceipt;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.JobStatusHistory;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus;
import com.workernotfound.job.domain.job.entity.enums.RefundReviewReason;
import com.workernotfound.job.domain.job.repository.JobPaymentRefundReviewRepository;
import com.workernotfound.job.domain.job.service.JobPaymentOrderCommandIssuer;
import com.workernotfound.job.support.FundingStatusApiTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예치 상태 수신 API를 실제 컨트롤러·내부 인증 필터·MySQL로 검증한다. 공고는 실제 등록과 주문 생성·연결 경로로 준비한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class FundingStatusApiTests extends FundingStatusApiTestSupport {

    @Autowired
    private JobPaymentRefundReviewRepository refundReviewRepository;

    @Autowired
    private JobPaymentOrderCommandIssuer paymentOrderCommandIssuer;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void rejectsMissingOrWrongInternalSecretWithoutRecording() throws Exception {
        LinkedJob job = createLinkedJob(1);
        String json = "{\"orderId\":\"%s\",\"jobVersion\":1,\"ownerMemberId\":%d,\"amount\":%d,\"currency\":\"KRW\","
                .formatted(job.orderId(), job.ownerMemberId(), job.amount())
                + "\"fundingRevision\":1,\"funded\":true}";

        mockMvc.perform(post("/api/jobs/internal/{jobPostId}/funding-status", job.jobPostId())
                        .header("Idempotency-Key", fundingKey(job, 1))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));
        mockMvc.perform(post("/api/jobs/internal/{jobPostId}/funding-status", job.jobPostId())
                        .header("X-Internal-Secret", "wrong-secret")
                        .header("Idempotency-Key", fundingKey(job, 1))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GLOBAL-401-001"));

        assertUntouched(job);
    }

    @Test
    void rejectsInvalidRequestsWithoutRecordingOrChangingJob() throws Exception {
        LinkedJob job = createLinkedJob(1);
        Map<String, Object> valid = notice(job, 1, true);
        String key = fundingKey(job, 1);
        List<Map<String, Object>> invalidBodies = new ArrayList<>(List.of(
                with(valid, "funded", null),
                with(valid, "orderId", "주문"),
                with(valid, "orderId", "x".repeat(65)),
                with(valid, "jobVersion", 0),
                with(valid, "ownerMemberId", -1),
                with(valid, "amount", "100000.50"),
                with(valid, "amount", 99),
                with(valid, "amount", "100000000000000000"),
                with(valid, "currency", "krw"),
                with(valid, "fundingRevision", 0)
        ));
        for (String field : List.of("orderId", "jobVersion", "ownerMemberId", "amount", "currency",
                "fundingRevision", "funded")) {
            Map<String, Object> missing = new LinkedHashMap<>(valid);
            missing.remove(field);
            invalidBodies.add(missing);
        }

        for (Map<String, Object> body : invalidBodies) {
            sendFunding(job.jobPostId(), key, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
        // 소수 버전·문자열 불리언처럼 형 변환으로 다른 값이 될 수 있는 입력도 거절한다.
        String base = "{\"orderId\":\"%s\",\"ownerMemberId\":%d,\"amount\":%d,\"currency\":\"KRW\",\"fundingRevision\":1,"
                .formatted(job.orderId(), job.ownerMemberId(), job.amount());
        for (String fields : List.of(
                "\"jobVersion\":1.5,\"funded\":true}",
                "\"jobVersion\":1.0,\"funded\":true}",
                "\"jobVersion\":\"1\",\"funded\":true}",
                "\"jobVersion\":1,\"funded\":\"true\"}",
                "\"jobVersion\":1,\"funded\":1}")) {
            sendFundingJson(job.jobPostId(), key, base + fields).andExpect(status().isBadRequest());
        }
        sendFundingJson(job.jobPostId(), key, "{not json").andExpect(status().isBadRequest());
        sendFunding(job.jobPostId(), "bad key", valid).andExpect(status().isBadRequest());
        sendFunding(job.jobPostId(), "k".repeat(101), valid).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/jobs/internal/{jobPostId}/funding-status", job.jobPostId())
                        .header("X-Internal-Secret", INTERNAL_SECRET)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        assertUntouched(job);
        // 거절한 요청이 키를 선점하지 않으므로 같은 키의 정상 알림은 처리된다.
        sendFunding(job.jobPostId(), key, valid)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));
    }

    @Test
    void publishesPendingJobOnceWithHistoryAfterOrderCreationAndLink() throws Exception {
        LinkedJob job = createLinkedJob(2);
        Long versionBeforeFunding = jobPost(job.jobPostId()).getVersion();
        // 주문 연결로 @Version이 올라도 결제용 버전은 주문 생성 당시 값이다.
        assertThat(versionBeforeFunding).isGreaterThan(job.paymentJobVersion());

        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobPostId").value(job.jobPostId()))
                .andExpect(jsonPath("$.data.orderId").value(job.orderId()))
                .andExpect(jsonPath("$.data.fundingRevision").value(1))
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.jobStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.fundingBlocked").value(false));

        JobPost published = jobPost(job.jobPostId());
        assertThat(published.getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(published.isFundingBlocked()).isFalse();
        assertThat(published.getPaymentJobVersion()).isEqualTo(job.paymentJobVersion());
        List<JobStatusHistory> histories = historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId());
        assertThat(histories).singleElement().satisfies(history -> {
            assertThat(history.getFromStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
            assertThat(history.getToStatus()).isEqualTo(JobStatus.OPEN);
            assertThat(history.getReason()).isEqualTo("SYSTEM:FUNDING_CONFIRMED");
        });
        assertThat(fundingRepository.findByOrderId(job.orderId()).orElseThrow().getFundingRevision()).isEqualTo(1L);
        assertThat(receiptRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).singleElement()
                .satisfies(receipt -> assertThat(receipt.isRefundReviewRequired()).isFalse());
        // 공개 후에는 검색·신규 지원·자리 예약 대상이다.
        requestAdmission(job.jobPostId(), newKey("admission"), 100L).andExpect(status().isOk());
        reserveSeat(job.jobPostId(), newKey("seat")).andExpect(status().isOk());
    }

    @Test
    void replaysOriginalResponseForSameKeyAfterVersionIncreaseAndClosing() throws Exception {
        LinkedJob job = createLinkedJob(1);
        String key = fundingKey(job, 1);
        String original = sendFunding(job.jobPostId(), key, notice(job, 1, true))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Long versionAfterPublish = jobPost(job.jobPostId()).getVersion();

        // 공개 이후 마지막 자리 확정으로 공고가 마감되고 @Version이 다시 증가한다.
        String reservationId = reservationId(reserveSeat(job.jobPostId(), newKey("seat")));
        seatCommand(job.jobPostId(), reservationId, "confirm", newKey("confirm")).andExpect(status().isOk());
        JobPost closed = jobPost(job.jobPostId());
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(closed.getVersion()).isGreaterThan(versionAfterPublish);

        String replay = sendFunding(job.jobPostId(), key, notice(job, 1, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.jobStatus").value("OPEN"))
                .andReturn().getResponse().getContentAsString();

        assertThat(dataOf(replay)).isEqualTo(dataOf(original));
        assertThat(jobPost(job.jobPostId()).getVersion()).isEqualTo(closed.getVersion());
        assertThat(receiptRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).hasSize(1);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId()))
                .extracting(JobStatusHistory::getToStatus)
                .containsExactly(JobStatus.OPEN, JobStatus.CLOSED);
    }

    @Test
    void rejectsSameKeyReusedForDifferentContentOrJob() throws Exception {
        LinkedJob job = createLinkedJob(1);
        LinkedJob other = createLinkedJob(1);
        String key = fundingKey(job, 1);
        sendFunding(job.jobPostId(), key, notice(job, 1, true)).andExpect(status().isOk());

        sendFunding(job.jobPostId(), key, notice(job, 1, false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-004"));
        sendFunding(job.jobPostId(), key, notice(job, 2, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-004"));
        sendFunding(other.jobPostId(), key, notice(other, 1, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-004"));

        assertThat(jobPost(job.jobPostId()).isFundingBlocked()).isFalse();
        assertThat(fundingRepository.findByOrderId(job.orderId()).orElseThrow().getFundingRevision()).isEqualTo(1L);
        assertUntouched(other);
    }

    @Test
    void appliesSameOrderRevisionOnceAcrossDifferentKeys() throws Exception {
        LinkedJob job = createLinkedJob(1);
        String original = sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String duplicate = sendFunding(job.jobPostId(), newKey("other-sender"), notice(job, 1, true))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(dataOf(duplicate)).isEqualTo(dataOf(original));
        assertThat(receiptRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).hasSize(1);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).hasSize(1);
    }

    @Test
    void rejectsSameOrderRevisionWithDifferentContentAsConflict() throws Exception {
        LinkedJob job = createLinkedJob(1);
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true)).andExpect(status().isOk());
        Long version = jobPost(job.jobPostId()).getVersion();

        sendFunding(job.jobPostId(), newKey("other-sender"), notice(job, 1, false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-012"));

        JobPost unchanged = jobPost(job.jobPostId());
        assertThat(unchanged.isFundingBlocked()).isFalse();
        assertThat(unchanged.getVersion()).isEqualTo(version);
        // 충돌 판정 기준인 처음 수신 기록을 보존한다.
        assertThat(receiptRepository.findByOrderIdAndFundingRevision(job.orderId(), 1L).orElseThrow().isFunded())
                .isTrue();
    }

    @Test
    void keepsBlockWhenLowerTrueRevisionArrivesAfterHigherFalseRevision() throws Exception {
        LinkedJob job = createLinkedJob(1);

        sendFunding(job.jobPostId(), fundingKey(job, 2), notice(job, 2, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("FUNDING_BLOCKED"))
                .andExpect(jsonPath("$.data.jobStatus").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.fundingBlocked").value(true));
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("STALE_REVISION"))
                .andExpect(jsonPath("$.data.jobStatus").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.data.fundingBlocked").value(true));
        // 같은 주문·revision의 다른 키는 처음 결과를 돌려받고 상태를 다시 바꾸지 않는다.
        sendFunding(job.jobPostId(), newKey("other-sender"), notice(job, 2, false))
                .andExpect(jsonPath("$.data.result").value("FUNDING_BLOCKED"));

        JobPost blocked = jobPost(job.jobPostId());
        assertThat(blocked.getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        assertThat(blocked.isFundingBlocked()).isTrue();
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).isEmpty();
        assertThat(fundingRepository.findByOrderId(job.orderId()).orElseThrow().getFundingRevision()).isEqualTo(2L);

        // 더 높은 true revision은 차단을 풀고, 결제 대기이며 공개 기한 안이면 공개한다.
        sendFunding(job.jobPostId(), fundingKey(job, 3), notice(job, 3, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.fundingBlocked").value(false));
        assertThat(jobPost(job.jobPostId()).getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).hasSize(1);
    }

    @Test
    void higherTrueRevisionUnblocksOpenJobWithoutStatusTransition() throws Exception {
        LinkedJob job = createLinkedJob(2);
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true)).andExpect(status().isOk());
        sendFunding(job.jobPostId(), fundingKey(job, 2), notice(job, 2, false))
                .andExpect(jsonPath("$.data.result").value("FUNDING_BLOCKED"))
                .andExpect(jsonPath("$.data.jobStatus").value("OPEN"));
        requestAdmission(job.jobPostId(), newKey("admission"), 100L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-001"));

        sendFunding(job.jobPostId(), fundingKey(job, 3), notice(job, 3, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("FUNDING_CONFIRMED"))
                .andExpect(jsonPath("$.data.jobStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.fundingBlocked").value(false));

        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).hasSize(1);
        requestAdmission(job.jobPostId(), newKey("admission"), 100L).andExpect(status().isOk());
    }

    @Test
    void doesNotReopenClosedJobWhenFundingRecovers() throws Exception {
        LinkedJob job = createLinkedJob(1);
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true)).andExpect(status().isOk());
        String reservationId = reservationId(reserveSeat(job.jobPostId(), newKey("seat")));
        seatCommand(job.jobPostId(), reservationId, "confirm", newKey("confirm")).andExpect(status().isOk());
        assertThat(jobPost(job.jobPostId()).getStatus()).isEqualTo(JobStatus.CLOSED);

        sendFunding(job.jobPostId(), fundingKey(job, 2), notice(job, 2, false))
                .andExpect(jsonPath("$.data.result").value("FUNDING_BLOCKED"))
                .andExpect(jsonPath("$.data.jobStatus").value("CLOSED"));
        sendFunding(job.jobPostId(), fundingKey(job, 3), notice(job, 3, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.result").value("PUBLICATION_SKIPPED"))
                .andExpect(jsonPath("$.data.skipReason").value("JOB_CLOSED"))
                .andExpect(jsonPath("$.data.jobStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.fundingBlocked").value(false));

        JobPost closed = jobPost(job.jobPostId());
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId()))
                .extracting(JobStatusHistory::getToStatus)
                .containsExactly(JobStatus.OPEN, JobStatus.CLOSED);
        assertThat(receiptRepository.findByOrderIdAndFundingRevision(job.orderId(), 3L).orElseThrow()
                .isRefundReviewRequired()).isTrue();
        // 예치 차단 해제가 기존 확정 자리나 마감 상태를 되돌리지 않는다.
        seatCommand(job.jobPostId(), reservationId, "release", newKey("release"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-010"));
    }

    @Test
    void keepsLatestOrderFundingWhenEarlierOrderNotificationArrives() throws Exception {
        LinkedJob earlier = createLinkedJob(1);
        LinkedJob latest = relinkWithNewOrder(earlier);
        sendFunding(latest.jobPostId(), fundingKey(latest, 1), notice(latest, 1, true))
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));

        sendFunding(earlier.jobPostId(), fundingKey(earlier, 5), notice(earlier, 5, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("STALE_ORDER"))
                .andExpect(jsonPath("$.data.fundingBlocked").value(false));
        sendFunding(earlier.jobPostId(), fundingKey(earlier, 6), notice(earlier, 6, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("STALE_ORDER"));

        JobPost job = jobPost(latest.jobPostId());
        assertThat(job.getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(job.isFundingBlocked()).isFalse();
        assertThat(job.getPaymentOrderId()).isEqualTo(latest.orderId());
        assertThat(fundingRepository.findByOrderId(earlier.orderId())).isEmpty();
        assertThat(fundingRepository.findByOrderId(latest.orderId()).orElseThrow().getFundingRevision()).isEqualTo(1L);
        // 이전 주문에 남은 예치 확인만 환불 검토 대상이다.
        assertThat(receiptRepository.findByOrderIdAndFundingRevision(earlier.orderId(), 5L).orElseThrow()
                .isRefundReviewRequired()).isFalse();
        assertThat(receiptRepository.findByOrderIdAndFundingRevision(earlier.orderId(), 6L).orElseThrow()
                .isRefundReviewRequired()).isTrue();
        // 환불 검토 대상은 주문당 한 건이며 처음 검토 대상이 된 수신 기록에 연결한다.
        sendFunding(earlier.jobPostId(), fundingKey(earlier, 7), notice(earlier, 7, true))
                .andExpect(jsonPath("$.data.result").value("STALE_ORDER"));
        assertThat(refundReviewRepository.findByJobPostIdOrderByIdAsc(job.getId())).singleElement()
                .satisfies(review -> {
                    assertThat(review.getOrderId()).isEqualTo(earlier.orderId());
                    assertThat(review.getReason()).isEqualTo(RefundReviewReason.STALE_ORDER);
                    assertThat(review.getReceiptId()).isEqualTo(receiptRepository
                            .findByOrderIdAndFundingRevision(earlier.orderId(), 6L).orElseThrow().getId());
                });
    }

    @Test
    void asksForRetryBeforeOrderLinkAndAppliesSameCommandAfterLink() throws Exception {
        JobPaymentOrderCommand command = createPendingJob(1);
        // payment-service는 주문을 만들었지만 응답이 유실되어 job-service는 아직 주문 ID를 모른다.
        PAYMENT_SERVER.enqueueProcessedButDelayed(CALL_TIMEOUT.multipliedBy(2));
        assertThat(paymentOrderDispatcher.dispatch(command.getId())).isTrue();
        assertThat(commandRepository.findById(command.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderCommandStatus.PENDING);
        String orderId = PAYMENT_SERVER.issuedOrderId(command.getIdempotencyKey());
        assertThat(orderId).isNotNull();
        assertThat(jobPost(command.getJobPostId()).getPaymentOrderId()).isNull();
        LinkedJob job = new LinkedJob(command.getJobPostId(), orderId, command.getJobVersion(),
                command.getOwnerMemberId(), command.getAmount());

        for (int attempt = 0; attempt < 2; attempt++) {
            sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("JOB-409-013"));
        }
        // 알림 내용만으로 주문을 연결하거나 수신을 완료 처리하지 않는다.
        assertThat(jobPost(job.jobPostId()).getPaymentOrderId()).isNull();
        assertThat(jobPost(job.jobPostId()).getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        assertThat(receiptRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).isEmpty();
        assertThat(fundingRepository.findByOrderId(orderId)).isEmpty();
        // 스냅샷이 다른 주문은 연결 대기가 아니라 관련 없는 잘못된 주문이다.
        sendFunding(job.jobPostId(), newKey("unrelated"), with(notice(job, 1, true), "amount", job.amount() + 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-011"));

        clock.fixAt(clock.instant().plus(BASE_DELAY));
        assertThat(paymentOrderDispatcher.dispatch(command.getId())).isTrue();
        clock.reset();
        assertThat(commandRepository.findById(command.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentOrderCommandStatus.SUCCEEDED);

        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));
        assertThat(jobPost(job.jobPostId()).getStatus()).isEqualTo(JobStatus.OPEN);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).hasSize(1);
    }

    @Test
    void rejectsUnknownOrderAndSnapshotMismatchWithoutTakingRevision() throws Exception {
        LinkedJob job = createLinkedJob(1);
        Map<String, Object> valid = notice(job, 1, true);
        List<Map<String, Object>> mismatches = List.of(
                with(valid, "orderId", UUID.randomUUID().toString()),
                with(valid, "amount", job.amount() + 1),
                with(valid, "currency", "USD"),
                with(valid, "ownerMemberId", job.ownerMemberId() + 1),
                // 결제용 버전을 현재 @Version(주문 연결로 증가)과 비교하지 않는다.
                with(valid, "jobVersion", jobPost(job.jobPostId()).getVersion())
        );

        for (Map<String, Object> mismatch : mismatches) {
            sendFunding(job.jobPostId(), newKey("mismatch"), mismatch)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("JOB-409-011"));
        }
        // 같은 키로 잘못 보낸 알림도 최종 결과로 고정되지 않는다.
        sendFunding(job.jobPostId(), fundingKey(job, 1), with(valid, "amount", job.amount() + 1))
                .andExpect(status().isConflict());

        assertUntouched(job);
        // 금액 scale이 달라도 같은 정수 KRW면 같은 주문 스냅샷이다.
        sendFunding(job.jobPostId(), fundingKey(job, 1), with(valid, "amount", job.amount()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));
    }

    @Test
    void rejectsJobWithoutAnyPaymentOrder() throws Exception {
        JobPost legacy = jobPostRepository.save(JobPostFixture.open(JobPostFixture.jobPost().build()));
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "orderId", UUID.randomUUID().toString(), "jobVersion", 1, "ownerMemberId", 7,
                "amount", 90_000, "currency", "KRW", "fundingRevision", 1, "funded", false));

        sendFunding(legacy.getId(), newKey("legacy"), body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB-409-011"));
        sendFunding(999_999_999L, newKey("missing"), body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB-404-001"));

        assertThat(jobPost(legacy.getId()).isFundingBlocked()).isFalse();
    }

    @Test
    void skipsPublicationAtApplicationDeadlineAndPublishesJustBefore() throws Exception {
        LocalDate workDate = LocalDate.now().plusDays(3);
        LocalDateTime deadline = workDate.minusDays(1).atTime(LocalTime.NOON);
        LinkedJob atDeadline = createLinkedJob(1, workDate, LocalTime.of(9, 0), deadline);
        LinkedJob beforeDeadline = createLinkedJob(1, workDate, LocalTime.of(9, 0), deadline);

        fixClockAt(deadline);
        sendFunding(atDeadline.jobPostId(), fundingKey(atDeadline, 1), notice(atDeadline, 1, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLICATION_SKIPPED"))
                .andExpect(jsonPath("$.data.skipReason").value("APPLICATION_DEADLINE_PASSED"))
                .andExpect(jsonPath("$.data.jobStatus").value("PAYMENT_PENDING"));
        fixClockAt(deadline.minusNanos(1_000));
        sendFunding(beforeDeadline.jobPostId(), fundingKey(beforeDeadline, 1), notice(beforeDeadline, 1, true))
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));

        assertThat(jobPost(atDeadline.jobPostId()).getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(atDeadline.jobPostId())).isEmpty();
        JobFundingStatusReceipt skipped = receiptRepository.findByJobPostIdOrderByIdAsc(atDeadline.jobPostId()).get(0);
        assertThat(skipped.isRefundReviewRequired()).isTrue();
        assertThat(fundingRepository.findByOrderId(atDeadline.orderId()).orElseThrow().isFunded()).isTrue();
        assertThat(jobPost(beforeDeadline.jobPostId()).getStatus()).isEqualTo(JobStatus.OPEN);
    }

    @Test
    void skipsPublicationAtWorkStart() throws Exception {
        LocalDate workDate = LocalDate.now().plusDays(3);
        LocalDateTime workStart = workDate.atTime(LocalTime.of(9, 0));
        LinkedJob job = createLinkedJob(1, workDate, LocalTime.of(9, 0), workStart.minusHours(1));

        fixClockAt(workStart);
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLICATION_SKIPPED"))
                .andExpect(jsonPath("$.data.skipReason").value("WORK_STARTED"));

        assertThat(jobPost(job.jobPostId()).getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).isEmpty();
    }

    @Test
    void doesNotPublishClosedJobThatNeverOpened() throws Exception {
        LinkedJob job = createLinkedJob(1);
        // 공고 취소·수동 마감 API는 없다. 결제 대기에서 마감된 공고를 SQL로 재현한다.
        jdbcTemplate.update("update job_posts set status = 'CLOSED' where id = ?", job.jobPostId());

        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLICATION_SKIPPED"))
                .andExpect(jsonPath("$.data.skipReason").value("JOB_CLOSED"));

        assertThat(jobPost(job.jobPostId()).getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).isEmpty();
        assertThat(receiptRepository.findByJobPostIdOrderByIdAsc(job.jobPostId()).get(0).isRefundReviewRequired())
                .isTrue();
    }

    @Test
    void appliesConcurrentDuplicatesOnce() throws Exception {
        LinkedJob job = createLinkedJob(1);
        int requestCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                // 절반은 같은 키, 절반은 다른 키의 같은 주문·revision이다.
                String key = i % 2 == 0 ? fundingKey(job, 1) : newKey("concurrent");
                results.add(executor.submit(() -> {
                    start.await();
                    return sendFunding(job.jobPostId(), key, notice(job, 1, true)).andReturn();
                }));
            }
            start.countDown();
            for (Future<MvcResult> result : results) {
                MvcResult response = result.get(30, TimeUnit.SECONDS);
                assertThat(response.getResponse().getStatus()).isEqualTo(200);
                jsonPath("$.data.result").value("PUBLISHED").match(response);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(receiptRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).hasSize(1);
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).hasSize(1);
        assertThat(jobPost(job.jobPostId()).getStatus()).isEqualTo(JobStatus.OPEN);
    }

    @Test
    void rollsBackReceiptRevisionStatusAndHistoryTogether() throws Exception {
        LinkedJob job = createLinkedJob(1);
        Long versionBefore = jobPost(job.jobPostId()).getVersion();
        String trigger = "fail_funding_history_" + job.jobPostId();
        // 이 공고의 공개 이력 INSERT만 실패시킨다. 다른 테스트의 공고에는 영향을 주지 않는다.
        executeAsRoot("""
                CREATE TRIGGER %s BEFORE INSERT ON job_status_histories FOR EACH ROW
                BEGIN
                    IF NEW.job_post_id = %d THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'history insert blocked by test';
                    END IF;
                END
                """.formatted(trigger, job.jobPostId()));
        try {
            sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("GLOBAL-500-001"));
        } finally {
            executeAsRoot("DROP TRIGGER " + trigger);
        }

        JobPost rolledBack = jobPost(job.jobPostId());
        assertThat(rolledBack.getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        assertThat(rolledBack.getVersion()).isEqualTo(versionBefore);
        assertUntouched(job);
        // 실패가 최종 응답으로 고정되지 않아 같은 명령의 재시도가 처리된다.
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("PUBLISHED"));
    }

    @Test
    void rollsBackBlockAndRevisionWhenReceiptInsertFails() throws Exception {
        LinkedJob job = createLinkedJob(1);
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true)).andExpect(status().isOk());
        String trigger = "fail_funding_receipt_" + job.jobPostId();
        executeAsRoot("""
                CREATE TRIGGER %s BEFORE INSERT ON job_funding_status_receipts FOR EACH ROW
                BEGIN
                    IF NEW.job_post_id = %d THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'receipt insert blocked by test';
                    END IF;
                END
                """.formatted(trigger, job.jobPostId()));
        try {
            sendFunding(job.jobPostId(), fundingKey(job, 2), notice(job, 2, false))
                    .andExpect(status().isInternalServerError());
        } finally {
            executeAsRoot("DROP TRIGGER " + trigger);
        }

        assertThat(jobPost(job.jobPostId()).isFundingBlocked()).isFalse();
        assertThat(fundingRepository.findByOrderId(job.orderId()).orElseThrow().getFundingRevision()).isEqualTo(1L);
        assertThat(receiptRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).hasSize(1);
    }

    @Test
    void recordsUnpublishedFundingForRefundReviewOnceAndRollsBackWithReceipt() throws Exception {
        LinkedJob job = createLinkedJob(1);
        fixClockAt(jobPost(job.jobPostId()).getApplicationDeadline());
        String trigger = "fail_refund_review_" + job.jobPostId();
        executeAsRoot("""
                CREATE TRIGGER %s BEFORE INSERT ON job_payment_refund_reviews FOR EACH ROW
                BEGIN
                    IF NEW.job_post_id = %d THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'refund review insert blocked by test';
                    END IF;
                END
                """.formatted(trigger, job.jobPostId()));
        try {
            sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                    .andExpect(status().isInternalServerError());
        } finally {
            executeAsRoot("DROP TRIGGER " + trigger);
        }
        // 검토 기록 저장이 실패하면 수신 기록과 revision도 남지 않아 같은 명령이 다시 처리된다.
        assertUntouched(job);

        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(jsonPath("$.data.result").value("PUBLICATION_SKIPPED"));
        sendFunding(job.jobPostId(), fundingKey(job, 1), notice(job, 1, true))
                .andExpect(jsonPath("$.data.result").value("PUBLICATION_SKIPPED"));
        sendFunding(job.jobPostId(), fundingKey(job, 2), notice(job, 2, false)).andExpect(status().isOk());
        sendFunding(job.jobPostId(), fundingKey(job, 3), notice(job, 3, true))
                .andExpect(jsonPath("$.data.result").value("PUBLICATION_SKIPPED"));

        assertThat(refundReviewRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).singleElement()
                .satisfies(review -> {
                    assertThat(review.getOrderId()).isEqualTo(job.orderId());
                    assertThat(review.getReason()).isEqualTo(RefundReviewReason.APPLICATION_DEADLINE_PASSED);
                    assertThat(review.getReceiptId()).isEqualTo(receiptRepository
                            .findByOrderIdAndFundingRevision(job.orderId(), 1L).orElseThrow().getId());
                });
    }

    @Test
    void keepsSecretKeyAndBodyOutOfLogs(CapturedOutput output) throws Exception {
        LinkedJob job = createLinkedJob(1);
        String key = "funding-log-key-" + UUID.randomUUID();
        Map<String, Object> mismatch = with(notice(job, 1, true), "amount", 123_456_789L);

        sendFunding(job.jobPostId(), key, mismatch).andExpect(status().isConflict());

        assertThat(output.getAll())
                .contains("연결된 결제 주문의 스냅샷과 다른 예치 상태 알림")
                .doesNotContain(INTERNAL_SECRET)
                .doesNotContain(key)
                .doesNotContain("123456789");
    }

    // 지원 클래스의 공통 경로로 만든 공고에 재결제처럼 다음 순번의 주문 생성 명령을 발급하고 새 주문을 연결한다.
    private LinkedJob relinkWithNewOrder(LinkedJob earlier) {
        JobPaymentOrderCommand next = transactionTemplate.execute(status -> {
            JobPost locked = jobPostRepository.findByIdForUpdate(earlier.jobPostId()).orElseThrow();
            return paymentOrderCommandIssuer.issue(locked, earlier.amount(), LocalDateTime.now(clock));
        });
        assertThat(paymentOrderDispatcher.dispatch(next.getId())).isTrue();
        String orderId = PAYMENT_SERVER.issuedOrderId(next.getIdempotencyKey());
        assertThat(jobPost(earlier.jobPostId()).getPaymentOrderId()).isEqualTo(orderId).isNotEqualTo(earlier.orderId());
        return new LinkedJob(earlier.jobPostId(), orderId, next.getJobVersion(), next.getOwnerMemberId(), next.getAmount());
    }

    private void assertUntouched(LinkedJob job) {
        JobPost jobPost = jobPost(job.jobPostId());
        assertThat(jobPost.getStatus()).isEqualTo(JobStatus.PAYMENT_PENDING);
        assertThat(jobPost.isFundingBlocked()).isFalse();
        assertThat(receiptRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).isEmpty();
        assertThat(fundingRepository.findByOrderId(job.orderId())).isEmpty();
        assertThat(historyRepository.findByJobPostIdOrderByIdAsc(job.jobPostId())).isEmpty();
    }

    private void fixClockAt(LocalDateTime time) {
        clock.fixAt(time.atZone(ZoneId.systemDefault()).toInstant());
    }

    private Object dataOf(String responseBody) {
        return com.jayway.jsonpath.JsonPath.read(responseBody, "$.data");
    }

    private void executeAsRoot(String sql) throws Exception {
        try (Connection connection = openLockObserverConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
