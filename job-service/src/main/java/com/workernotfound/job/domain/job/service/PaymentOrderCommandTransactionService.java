package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.exception.PaymentOrderCreationException;
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

    private final JobPaymentOrderCommandRepository commandRepository;
    private final JobPostRepository jobPostRepository;

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
        return commandRepository.findByIdAndLeaseToken(id, leaseToken).map(PaymentOrderDispatch::from);
    }

    /**
     * 검증된 주문 ID를 명령에 기록하고 공고에 연결한다. 둘은 같은 트랜잭션이며 공고 행 → 명령 행 순서로 잠근다.
     *
     * <p>실행권 토큰이 다르거나 명령이 이미 처리됐으면 아무것도 바꾸지 않는다. 같은 공고에 더 늦게 발급된 명령이 있으면
     * 과거 명령의 결과가 최신 연결을 덮어쓰지 않도록 이 명령만 종료한다. 공고 상태는 바꾸지 않는다.
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
        command.succeed(orderId, now);
        jobPost.linkPaymentOrder(orderId, command.getJobVersion(), command.getAmount(), command.getCurrency());
        return PaymentOrderLinkResult.LINKED;
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

    // 새 명령 발급도 공고 행 잠금 아래에서 하므로, 잠금을 잡은 지금의 최대 순번이 최신 명령이다.
    private boolean isLatestIssued(JobPaymentOrderCommand command) {
        return commandRepository.findMaxIssueSequenceByJobPostId(command.getJobPostId())
                .map(command.getIssueSequence()::equals)
                .orElse(false);
    }
}
