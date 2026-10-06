package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.FundingStatusNotification;
import com.workernotfound.job.domain.job.entity.JobFundingStatusReceipt;
import com.workernotfound.job.domain.job.entity.JobPaymentChangeRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPaymentTerms;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.FundingSkipReason;
import com.workernotfound.job.domain.job.entity.enums.FundingStatusResult;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentChangeStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobPaymentChangeRequestRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentRefundReviewRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.global.exception.BusinessException;
import com.workernotfound.job.support.IntegrationTestSupport;
import com.workernotfound.job.support.JobPostFixture;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 마감된 결제 대기 공고에서 결제 조건 변경·재결제 요청, 진행 중이던 변경의 새 주문 연결, 최초 주문 연결 뒤 늦은 예치 확인이 기존 규칙대로
 * 거절·생략되는지 검증한다. 마감으로 공고가 다시 열리거나 조건이 바뀌지 않는다.
 */
class JobClosedPaymentTests extends IntegrationTestSupport {

    private static final AtomicLong SEQUENCE = new AtomicLong(1_400_000);
    private static final long AMOUNT = 90_000L;

    @Autowired
    private JobCloseCommandService closeService;

    @Autowired
    private JobPaymentChangeCommandService changeService;

    @Autowired
    private PaymentOrderCommandTransactionService orderTransactionService;

    @Autowired
    private JobPaymentOrderCommandIssuer commandIssuer;

    @Autowired
    private JobFundingStatusCommandService fundingService;

    @Autowired
    private JobPostRepository jobPostRepository;

    @Autowired
    private JobPaymentOrderCommandRepository commandRepository;

    @Autowired
    private JobPaymentChangeRequestRepository changeRequestRepository;

    @Autowired
    private JobPaymentRefundReviewRepository refundReviewRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Test
    void closedJobRejectsNewTermsChangeAndRepayment() {
        JobPost jobPost = saveLinkedPaymentPendingJob();
        close(jobPost);

        assertRejected(() -> changeService.changeTerms(
                jobPost.getId(), jobPost.getOwnerId(), raisedWageTerms(jobPost), newKey()));
        assertRejected(() -> changeService.retryPayment(jobPost.getId(), jobPost.getOwnerId(), newKey()));
        assertThat(commandRepository.findByJobPostIdOrderByIssueSequenceAsc(jobPost.getId())).isEmpty();
    }

    @Test
    void changeRequestedBeforeCloseIsNotAppliedWhenItsOrderArrives() {
        JobPost jobPost = saveLinkedPaymentPendingJob();
        JobPaymentChange change = changeService.changeTerms(
                jobPost.getId(), jobPost.getOwnerId(), raisedWageTerms(jobPost), newKey());
        close(jobPost);
        String newOrderId = "order-" + UUID.randomUUID();

        PaymentOrderLinkResult result = linkCreatedOrder(change.command(), newOrderId);

        assertThat(result).isEqualTo(PaymentOrderLinkResult.JOB_STATE_CHANGED);
        JobPost closed = reload(jobPost);
        assertThat(closed.getStatus()).isEqualTo(JobStatus.CLOSED);
        assertThat(closed.getBaseHourlyWage()).isEqualTo(jobPost.getBaseHourlyWage());
        assertThat(closed.getPaymentOrderId()).isEqualTo(jobPost.getPaymentOrderId());
        JobPaymentChangeRequest request = changeRequestRepository.findById(change.request().getId()).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(PaymentChangeStatus.REJECTED);
        assertThat(request.getResolutionCode()).isEqualTo(PaymentOrderCommandTransactionService.JOB_STATE_CHANGED);
        JobPaymentOrderCommand command = commandRepository.findById(change.command().getId()).orElseThrow();
        assertThat(command.getStatus()).isEqualTo(PaymentOrderCommandStatus.SUPERSEDED);
        assertThat(command.getOrderId()).isEqualTo(newOrderId);
    }

    @Test
    void initialOrderLinkedAfterCloseSendsLateFundingToRefundReview() {
        JobPost jobPost = jobPostRepository.save(JobPostFixture.jobPost().ownerId(nextId()).build());
        JobPaymentOrderCommand command = issueInitialCommand(jobPost);
        close(jobPost);
        String orderId = "order-" + UUID.randomUUID();

        // 최초 주문은 기존 규칙대로 상태와 관계없이 연결한다. 연결돼야 늦은 예치 확인이 연결 대기(409)에 갇히지 않는다.
        assertThat(linkCreatedOrder(command, orderId)).isEqualTo(PaymentOrderLinkResult.LINKED);
        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.CLOSED);

        JobFundingStatusReceipt receipt = fundingService.receive(jobPost.getId(), new FundingStatusNotification(
                orderId, command.getJobVersion(), jobPost.getOwnerId(), AMOUNT, "KRW", 1L, true), newKey());

        assertThat(receipt.getResult()).isEqualTo(FundingStatusResult.PUBLICATION_SKIPPED);
        assertThat(receipt.getSkipReason()).isEqualTo(FundingSkipReason.JOB_CLOSED);
        assertThat(receipt.isRefundReviewRequired()).isTrue();
        assertThat(refundReviewRepository.existsByOrderId(orderId)).isTrue();
        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.CLOSED);
    }

    private PaymentOrderLinkResult linkCreatedOrder(JobPaymentOrderCommand command, String orderId) {
        LocalDateTime now = LocalDateTime.now(clock);
        String leaseToken = UUID.randomUUID().toString();
        assertThat(orderTransactionService.claim(command.getId(), leaseToken, now, now.plusSeconds(30))).isPresent();
        return orderTransactionService.recordCreated(command.getId(), leaseToken, orderId, now);
    }

    private JobPaymentOrderCommand issueInitialCommand(JobPost jobPost) {
        return new TransactionTemplate(transactionManager).execute(status -> commandIssuer.issue(
                jobPostRepository.findByIdForUpdate(jobPost.getId()).orElseThrow(), AMOUNT, LocalDateTime.now(clock)));
    }

    private void assertRejected(Runnable request) {
        assertThatThrownBy(request::run)
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(JobErrorCode.PAYMENT_CHANGE_NOT_ALLOWED));
    }

    private JobPaymentTerms raisedWageTerms(JobPost jobPost) {
        JobPaymentTerms current = jobPost.paymentTerms();
        return new JobPaymentTerms(current.workDate(), current.startTime(), current.endTime(), current.endTimeNextDay(),
                current.baseHourlyWage() + 1_000, current.extraWage(), current.recruitCount(),
                current.applicationDeadline());
    }

    private void close(JobPost jobPost) {
        closeService.close(jobPost.getId(), jobPost.getOwnerId(), newKey());
        assertThat(reload(jobPost).getStatus()).isEqualTo(JobStatus.CLOSED);
    }

    private JobPost reload(JobPost jobPost) {
        return jobPostRepository.findById(jobPost.getId()).orElseThrow();
    }

    // 결제 스냅샷을 연결한 결제 대기 공고. 주문 생성·연결 경로는 결제 주문 테스트가 검증한다.
    private JobPost saveLinkedPaymentPendingJob() {
        JobPost jobPost = JobPostFixture.jobPost().ownerId(nextId()).build();
        jobPost.linkPaymentOrder("order-" + UUID.randomUUID(), 1L, AMOUNT, "KRW");
        return jobPostRepository.save(jobPost);
    }

    private static long nextId() {
        return SEQUENCE.incrementAndGet();
    }

    private static String newKey() {
        return "closed-payment-" + UUID.randomUUID();
    }
}
