package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPaymentChangeRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentChangeType;
import com.workernotfound.job.domain.job.exception.PaymentOrderCreationException;
import com.workernotfound.job.domain.job.repository.JobPaymentChangeRequestRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentFundingRepository;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import com.workernotfound.job.domain.job.repository.JobPostRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 주문 생성 명령의 짧은 상태 변경 트랜잭션.
 *
 * <p>실행권 획득과 결과 기록을 각각 따로 커밋한다. HTTP 호출은 이 트랜잭션들 사이, 트랜잭션 밖에서 실행한다.
 * 결과 기록은 실행권 토큰이 일치할 때만 반영되므로 실행권을 넘겨받은 새 실행자의 상태를 이전 실행자가 덮어쓰지 못한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentOrderCommandTransactionService {

    // 주문 교체가 확인되지 않았을 때 결제 변경 요청에 남기는 사유
    static final String JOB_STATE_CHANGED = "JOB_STATE_CHANGED";

    private final JobPaymentOrderCommandRepository commandRepository;
    private final JobPostRepository jobPostRepository;
    private final JobPaymentChangeRequestRepository changeRequestRepository;
    private final JobPaymentFundingRepository fundingRepository;

    @Transactional
    public Optional<PaymentOrderDispatch> claim(
            Long id,
            String leaseToken,
            LocalDateTime now,
            LocalDateTime leaseExpiresAt
    ) {
        if (commandRepository.claim(id, leaseToken, now, leaseExpiresAt) == 0) {
            return Optional.empty();
        }
        boolean isReplacement = changeRequestRepository.existsByCommandId(id);
        return commandRepository.findByIdAndLeaseToken(id, leaseToken)
                .map(command -> PaymentOrderDispatch.from(command, isReplacement));
    }

    /**
     * 검증된 주문 ID를 명령에 기록하고 공고에 연결한다. 둘은 같은 트랜잭션이며 공고 행 → 명령 행 → 결제 변경 요청 행 순서로 잠근다.
     *
     * <p>실행권 토큰이 다르거나 명령이 이미 처리됐으면 아무것도 바꾸지 않는다. 같은 공고에 더 늦게 발급된 명령이 있으면
     * 과거 명령의 결과가 최신 연결을 덮어쓰지 않도록 이 명령만 종료한다. 결제 조건 변경 명령이면 같은 트랜잭션에서 대기 중인 조건을
     * 공고에 적용한다. 공고 상태와 예치 차단은 바꾸지 않으며, 새 주문의 예치가 확인될 때까지 공고는 비공개로 남는다.
     */
    @Transactional
    public PaymentOrderLinkResult recordCreated(Long id, String leaseToken, String orderId, LocalDateTime now) {
        Long jobPostId = commandRepository.findJobPostIdById(id)
                .orElseThrow(() -> new IllegalStateException("결제 주문 생성 명령이 없습니다: id=" + id));
        JobPost jobPost = jobPostRepository.findByIdForUpdate(jobPostId)
                .orElseThrow(() -> new IllegalStateException("결제 주문 생성 명령의 공고가 없습니다: id=" + id));
        JobPaymentOrderCommand command = commandRepository.findByIdForUpdate(id).orElseThrow();
        if (!command.isLeasedBy(leaseToken)) {
            return PaymentOrderLinkResult.LEASE_LOST;
        }
        if (!isLatestIssued(command)) {
            command.supersede(now);
            return PaymentOrderLinkResult.SUPERSEDED;
        }
        Optional<JobPaymentChangeRequest> change = changeRequestRepository.findByCommandIdForUpdate(id);
        if (change.isPresent() && !isStillReplaceable(jobPost)) {
            // payment-service가 교체를 허용했는데 공고가 이미 이전 주문으로 공개됐거나 그 예치 상태가 반영된 경우다.
            // 정상 교체 정책에서는 생기지 않으므로 공개된 조건을 바꾸지 않고, 주문 ID만 남겨 이 주문의 늦은 알림을 이전 주문으로 다룬다.
            command.supersedeUnlinked(orderId, now);
            change.get().reject(JOB_STATE_CHANGED, now);
            return PaymentOrderLinkResult.JOB_STATE_CHANGED;
        }
        command.succeed(orderId, now);
        jobPost.linkPaymentOrder(orderId, command.getJobVersion(), command.getAmount(), command.getCurrency());
        change.ifPresent(request -> apply(jobPost, request, now));
        return PaymentOrderLinkResult.LINKED;
    }

    /**
     * payment-service가 결제 조건 변경·재결제 명령의 주문 교체를 확정적으로 거절했다(409, 주문 미생성). 명령과 요청을 거절로 종료하고
     * 공고의 현재 조건과 주문 연결은 그대로 둔다. 실행권 토큰이 다르거나 이미 처리된 명령이면 아무것도 바꾸지 않는다.
     */
    @Transactional
    public boolean recordRejected(Long id, String leaseToken, PaymentOrderCreationException failure, LocalDateTime now) {
        Long jobPostId = commandRepository.findJobPostIdById(id)
                .orElseThrow(() -> new IllegalStateException("결제 주문 생성 명령이 없습니다: id=" + id));
        jobPostRepository.findByIdForUpdate(jobPostId)
                .orElseThrow(() -> new IllegalStateException("결제 주문 생성 명령의 공고가 없습니다: id=" + id));
        JobPaymentOrderCommand command = commandRepository.findByIdForUpdate(id).orElseThrow();
        if (!command.isLeasedBy(leaseToken)) {
            return false;
        }
        JobPaymentChangeRequest change = changeRequestRepository.findByCommandIdForUpdate(id)
                .orElseThrow(() -> new IllegalStateException("결제 변경 요청이 없는 명령은 거절로 종료하지 않습니다: id=" + id));
        command.reject(failure.getFailureType(), failure.getHttpStatus(), failure.getResponseCode(), now);
        change.reject(failure.getResponseCode() == null ? failure.getFailureType().name() : failure.getResponseCode(), now);
        return true;
    }

    @Transactional
    public boolean markFailed(
            Long id,
            String leaseToken,
            PaymentOrderCreationException failure,
            LocalDateTime nextAttemptAt,
            LocalDateTime now
    ) {
        return commandRepository.markFailed(
                id,
                leaseToken,
                failure.getFailureType(),
                failure.getHttpStatus(),
                failure.getResponseCode(),
                nextAttemptAt,
                now
        ) == 1;
    }

    private void apply(JobPost jobPost, JobPaymentChangeRequest request, LocalDateTime now) {
        if (request.getChangeType() == PaymentChangeType.TERMS_CHANGE) {
            jobPost.applyPaymentTerms(request.terms());
        }
        request.apply(now);
    }

    // 요청을 받을 때와 같은 기준이다. 결제 대기 공고이고 현재 연결된 주문에 예치 상태가 반영된 적이 없어야 한다.
    // 공고 잠금 전의 일반 조회가 읽기 스냅샷을 만들었으므로 예치 상태는 잠금 조회로 커밋된 최신 값을 읽는다.
    private boolean isStillReplaceable(JobPost jobPost) {
        return jobPost.getStatus() == JobStatus.PAYMENT_PENDING
                && !jobPost.isFundingBlocked()
                && (jobPost.getPaymentOrderId() == null
                || fundingRepository.findCommittedByOrderId(jobPost.getPaymentOrderId()).isEmpty());
    }

    // 새 명령 발급도 공고 행 잠금 아래에서 하므로, 잠금을 잡은 지금의 최대 순번이 최신 명령이다.
    // 앞선 일반 조회가 만든 읽기 스냅샷이 아니라 잠금 조회로 커밋된 최신 값을 읽는다.
    private boolean isLatestIssued(JobPaymentOrderCommand command) {
        return commandRepository.findCommittedMaxIssueSequenceByJobPostId(command.getJobPostId())
                .map(command.getIssueSequence()::equals)
                .orElse(false);
    }
}
