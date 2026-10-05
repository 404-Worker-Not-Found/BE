package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.FundingStatusNotification;
import com.workernotfound.job.domain.job.entity.JobFundingStatusReceipt;
import com.workernotfound.job.domain.job.entity.JobPaymentFunding;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPaymentRefundReview;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.JobStatusHistory;
import com.workernotfound.job.domain.job.entity.enums.FundingSkipReason;
import com.workernotfound.job.domain.job.entity.enums.FundingStatusResult;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentOrderCommandStatus;
import com.workernotfound.job.domain.job.entity.enums.RefundReviewReason;
import com.workernotfound.job.domain.job.exception.JobErrorCode;
import com.workernotfound.job.domain.job.repository.JobFundingStatusReceiptRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentFundingRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentRefundReviewRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import com.workernotfound.job.domain.job.repository.JobStatusHistoryRepository;
import com.workernotfound.job.global.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment-service가 검증한 예치 상태 알림을 받아 공고의 예치 상태와 공개 여부에 반영한다.
 *
 * <p>공고 행 잠금 하나로 같은 공고의 수신, 지원 승인, 자리 예약·확정을 직렬화한다. 수신 기록, 주문별 revision, 예치 차단, 공개 전이와
 * 상태 이력은 이 잠금 아래 한 로컬 트랜잭션에서 함께 커밋되거나 함께 롤백된다. 검증을 통과한 알림만 기록하므로 연결 대기·주문 불일치
 * 알림이 키나 revision을 선점해 이후의 정상 알림을 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobFundingStatusCommandService {

    static final String FUNDING_CONFIRMED_REASON = "SYSTEM:FUNDING_CONFIRMED";

    private final JobPostRepository jobPostRepository;
    private final JobFundingStatusReceiptRepository receiptRepository;
    private final JobPaymentFundingRepository fundingRepository;
    private final JobPaymentOrderCommandRepository commandRepository;
    private final JobStatusHistoryRepository historyRepository;
    private final JobPaymentRefundReviewRepository refundReviewRepository;
    private final Clock clock;

    @Transactional
    public JobFundingStatusReceipt receive(
            Long jobPostId,
            FundingStatusNotification notification,
            String idempotencyKey
    ) {
        // 공고 행 잠금이 트랜잭션의 첫 조회여야, 이후 일반 조회가 잠금을 기다리는 동안 커밋된 수신·주문 연결을 본다.
        JobPost jobPost = jobPostRepository.findByIdForUpdate(jobPostId)
                .orElseThrow(() -> new BusinessException(JobErrorCode.JOB_NOT_FOUND));
        Optional<JobFundingStatusReceipt> processed = findProcessed(jobPostId, notification, idempotencyKey);
        if (processed.isPresent()) {
            return processed.get();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Outcome outcome = process(jobPost, notification, now);
        JobFundingStatusReceipt receipt = receiptRepository.save(JobFundingStatusReceipt.builder()
                .idempotencyKey(idempotencyKey)
                .jobPostId(jobPostId)
                .notification(notification)
                .result(outcome.result())
                .skipReason(outcome.skipReason())
                .jobStatus(jobPost.getStatus())
                .fundingBlocked(jobPost.isFundingBlocked())
                .refundReviewRequired(outcome.isRefundReviewRequired())
                .receivedAt(now)
                .build());
        if (outcome.isRefundReviewRequired()) {
            recordRefundReview(receipt);
        }
        return receipt;
    }

    // 주문당 한 건만 남긴다. 같은 주문의 이후 예치 확인은 처음 검토 기록에 포함되며, 공고 상태는 바꾸지 않는다.
    private void recordRefundReview(JobFundingStatusReceipt receipt) {
        if (refundReviewRepository.existsByOrderId(receipt.getOrderId())) {
            return;
        }
        refundReviewRepository.save(JobPaymentRefundReview.builder()
                .jobPostId(receipt.getJobPostId())
                .orderId(receipt.getOrderId())
                .receiptId(receipt.getId())
                .reason(RefundReviewReason.of(receipt.getResult(), receipt.getSkipReason()))
                .build());
    }

    // 업무 상태를 보기 전에 이미 처리한 명령인지 확인한다. 공개 후 @Version이 올랐거나 공고가 마감돼도 처음 응답을 돌려준다.
    private Optional<JobFundingStatusReceipt> findProcessed(
            Long jobPostId,
            FundingStatusNotification notification,
            String idempotencyKey
    ) {
        Optional<JobFundingStatusReceipt> sameKey = receiptRepository.findByIdempotencyKey(idempotencyKey);
        if (sameKey.isPresent()) {
            if (!sameKey.get().isSameNotification(jobPostId, notification)) {
                throw new BusinessException(JobErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return sameKey;
        }
        // 다른 키로 같은 주문·revision이 와도 효과는 한 번뿐이다. 내용이 다르면 정상 중복으로 숨기지 않고 충돌로 거절한다.
        Optional<JobFundingStatusReceipt> sameRevision = receiptRepository.findByOrderIdAndFundingRevision(
                notification.orderId(), notification.fundingRevision());
        if (sameRevision.isPresent() && !sameRevision.get().isSameNotification(jobPostId, notification)) {
            log.warn("[운영 확인 필요] 같은 주문·revision의 예치 상태 알림 내용이 다릅니다: jobPostId={}, orderId={}, fundingRevision={}",
                    jobPostId, notification.orderId(), notification.fundingRevision());
            throw new BusinessException(JobErrorCode.FUNDING_REVISION_CONFLICT);
        }
        return sameRevision;
    }

    private Outcome process(JobPost jobPost, FundingStatusNotification notification, LocalDateTime now) {
        if (jobPost.isLinkedToPaymentOrder(notification.orderId())) {
            requireLinkedSnapshot(jobPost, notification);
            return applyToLinkedOrder(jobPost, notification, now);
        }
        if (isEarlierOrder(jobPost, notification)) {
            // 이전 주문의 예치 사실은 보존하되 최신 주문의 예치 상태는 바꾸지 않는다. 남은 예치 확인은 환불 검토 대상이다.
            return new Outcome(FundingStatusResult.STALE_ORDER, null, notification.funded());
        }
        if (isAwaitingOrderLink(jobPost, notification)) {
            // 주문 생성 응답이 유실됐거나 연결이 늦은 경우다. 알림만 믿고 연결하지 않고, 기록 없이 재시도를 요청한다.
            log.info("결제 주문 연결 전 예치 상태 알림: jobPostId={}, orderId={}, fundingRevision={}",
                    jobPost.getId(), notification.orderId(), notification.fundingRevision());
            throw new BusinessException(JobErrorCode.FUNDING_ORDER_LINK_PENDING);
        }
        log.warn("[운영 확인 필요] 공고의 결제 주문과 일치하지 않는 예치 상태 알림: jobPostId={}, orderId={}, fundingRevision={}",
                jobPost.getId(), notification.orderId(), notification.fundingRevision());
        throw new BusinessException(JobErrorCode.FUNDING_ORDER_MISMATCH);
    }

    private void requireLinkedSnapshot(JobPost jobPost, FundingStatusNotification notification) {
        boolean isSameSnapshot = jobPost.hasPaymentSnapshot(
                notification.jobVersion(), notification.ownerMemberId(), notification.amount(), notification.currency());
        if (!isSameSnapshot) {
            log.warn("[운영 확인 필요] 연결된 결제 주문의 스냅샷과 다른 예치 상태 알림: jobPostId={}, orderId={}, fundingRevision={}",
                    jobPost.getId(), notification.orderId(), notification.fundingRevision());
            throw new BusinessException(JobErrorCode.FUNDING_ORDER_MISMATCH);
        }
    }

    private Outcome applyToLinkedOrder(JobPost jobPost, FundingStatusNotification notification, LocalDateTime now) {
        Optional<JobPaymentFunding> funding = fundingRepository.findByOrderId(notification.orderId());
        if (funding.isPresent() && !funding.get().isOlderThan(notification.fundingRevision())) {
            return new Outcome(FundingStatusResult.STALE_REVISION, null, false);
        }
        recordRevision(jobPost, funding, notification, now);
        if (!notification.funded()) {
            // 상태를 PAYMENT_PENDING으로 되돌리지 않는다. 되돌리면 마감·취소 공고가 나중의 예치 확인으로 다시 공개될 수 있다.
            jobPost.blockFunding();
            return new Outcome(FundingStatusResult.FUNDING_BLOCKED, null, false);
        }
        // 예치 차단 해제와 공고 상태 전이는 따로 판단한다. 예치가 회복돼도 마감 공고는 다시 열지 않는다.
        jobPost.unblockFunding();
        return publishIfPossible(jobPost, now);
    }

    private void recordRevision(
            JobPost jobPost,
            Optional<JobPaymentFunding> funding,
            FundingStatusNotification notification,
            LocalDateTime now
    ) {
        if (funding.isPresent()) {
            funding.get().apply(notification.fundingRevision(), notification.funded(), now);
            return;
        }
        fundingRepository.save(JobPaymentFunding.builder()
                .jobPostId(jobPost.getId())
                .orderId(notification.orderId())
                .fundingRevision(notification.fundingRevision())
                .funded(notification.funded())
                .appliedAt(now)
                .build());
    }

    private Outcome publishIfPossible(JobPost jobPost, LocalDateTime now) {
        return switch (jobPost.getStatus()) {
            case OPEN, MATCHING -> new Outcome(FundingStatusResult.FUNDING_CONFIRMED, null, false);
            // 공개에 쓰이지 못한 예치 확인이다. 이미 확정된 매칭에 쓰였는지는 후속 환불·정산 검토가 판정한다.
            case CLOSED -> new Outcome(FundingStatusResult.PUBLICATION_SKIPPED, FundingSkipReason.JOB_CLOSED, true);
            case PAYMENT_PENDING -> findPublicationBlocker(jobPost, now)
                    .map(reason -> new Outcome(FundingStatusResult.PUBLICATION_SKIPPED, reason, true))
                    .orElseGet(() -> publish(jobPost, now));
        };
    }

    // 공개는 지원 마감과 근무 시작 전에만 한다. 둘 다 현재 시각과 같으면 이미 지난 것으로 본다.
    private Optional<FundingSkipReason> findPublicationBlocker(JobPost jobPost, LocalDateTime now) {
        if (!jobPost.getWorkDate().atTime(jobPost.getStartTime()).isAfter(now)) {
            return Optional.of(FundingSkipReason.WORK_STARTED);
        }
        if (!jobPost.getApplicationDeadline().isAfter(now)) {
            return Optional.of(FundingSkipReason.APPLICATION_DEADLINE_PASSED);
        }
        return Optional.empty();
    }

    private Outcome publish(JobPost jobPost, LocalDateTime now) {
        jobPost.publishAfterFunding();
        historyRepository.save(JobStatusHistory.builder()
                .jobPostId(jobPost.getId())
                .fromStatus(JobStatus.PAYMENT_PENDING)
                .toStatus(JobStatus.OPEN)
                .reason(FUNDING_CONFIRMED_REASON)
                .createdAt(now.truncatedTo(ChronoUnit.MICROS))
                .build());
        return new Outcome(FundingStatusResult.PUBLISHED, null, false);
    }

    // 주문 ID는 검증된 생성 응답을 연결할 때만 명령에 기록된다. 현재 연결과 다르면서 이 공고의 명령에 기록된 주문은 이전 주문이다.
    private boolean isEarlierOrder(JobPost jobPost, FundingStatusNotification notification) {
        return commandRepository.findByOrderId(notification.orderId())
                .filter(command -> command.getJobPostId().equals(jobPost.getId()))
                .filter(command -> hasCommandSnapshot(command, notification))
                .isPresent();
    }

    // 최신 명령이 아직 주문을 연결하지 못했고 스냅샷이 같으면 연결 대기다. 그 밖의 주문은 이 공고와 관련 없는 주문으로 본다.
    private boolean isAwaitingOrderLink(JobPost jobPost, FundingStatusNotification notification) {
        return commandRepository.findFirstByJobPostIdOrderByIssueSequenceDesc(jobPost.getId())
                .filter(command -> command.getStatus() == PaymentOrderCommandStatus.PENDING)
                .filter(command -> hasCommandSnapshot(command, notification))
                .isPresent();
    }

    private boolean hasCommandSnapshot(JobPaymentOrderCommand command, FundingStatusNotification notification) {
        return command.getJobVersion().equals(notification.jobVersion())
                && command.getOwnerMemberId().equals(notification.ownerMemberId())
                && command.getAmount().equals(notification.amount())
                && command.getCurrency().equals(notification.currency());
    }

    private record Outcome(FundingStatusResult result, FundingSkipReason skipReason, boolean isRefundReviewRequired) {
    }
}
