package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.event.PaymentOrderCommandCreatedEvent;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 주문 생성 명령을 새로 발급한다.
 *
 * <p>호출자의 트랜잭션에서 공고 저장·변경과 함께 커밋되거나 함께 롤백된다. 발급마다 새 멱등 키와 공고별 다음 순번을 쓴다.
 * 같은 생성 시도의 재시도는 발급이 아니라 저장된 명령의 재전송이므로 이 메서드를 다시 호출하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class JobPaymentOrderCommandIssuer {

    static final String CURRENCY_KRW = "KRW";

    private final JobPaymentOrderCommandRepository commandRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 기존 공고에 새 명령을 발급하는 후속 기능(재결제 등)은 같은 트랜잭션에서 공고 행 잠금을 먼저 잡아야 한다.
     * 순번 유일 제약이 동시 발급의 마지막 방어선이다.
     *
     * @param jobPost 이미 저장되어 ID와 현재 버전이 확정된 공고
     * @param amount  전체 예치 예정액(정수 KRW)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JobPaymentOrderCommand issue(JobPost jobPost, long amount, LocalDateTime now) {
        int nextSequence = commandRepository.findMaxIssueSequenceByJobPostId(jobPost.getId()).orElse(0) + 1;
        JobPaymentOrderCommand command = commandRepository.save(JobPaymentOrderCommand.builder()
                .jobPostId(jobPost.getId())
                .issueSequence(nextSequence)
                .idempotencyKey(UUID.randomUUID().toString())
                .jobVersion(jobPost.getVersion())
                .ownerMemberId(jobPost.getOwnerId())
                .amount(amount)
                .currency(CURRENCY_KRW)
                .nextAttemptAt(now)
                .build());
        eventPublisher.publishEvent(new PaymentOrderCommandCreatedEvent(command.getId()));
        return command;
    }
}
