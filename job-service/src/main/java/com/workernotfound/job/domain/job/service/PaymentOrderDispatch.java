package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.port.PaymentOrderCreator.PaymentOrderRequest;

// 실행권을 얻은 한 번의 전송 시도. 전송 값은 저장된 명령 스냅샷에서만 읽고 현재 공고를 다시 읽지 않는다.
public record PaymentOrderDispatch(
        Long id,
        Long jobPostId,
        int attemptCount,
        String leaseToken,
        PaymentOrderRequest request
) {

    static PaymentOrderDispatch from(JobPaymentOrderCommand command) {
        return new PaymentOrderDispatch(
                command.getId(),
                command.getJobPostId(),
                command.getAttemptCount(),
                command.getLeaseToken(),
                new PaymentOrderRequest(
                        command.getIdempotencyKey(),
                        command.getJobPostId(),
                        command.getJobVersion(),
                        command.getOwnerMemberId(),
                        command.getAmount(),
                        command.getCurrency()
                )
        );
    }
}
