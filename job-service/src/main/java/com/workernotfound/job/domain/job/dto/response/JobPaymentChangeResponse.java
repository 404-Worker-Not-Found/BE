package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.JobPaymentChangeRequest;
import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.enums.PaymentChangeStatus;
import com.workernotfound.job.domain.job.entity.enums.PaymentChangeType;
import java.time.LocalDateTime;

/**
 * 결제 조건 변경·재결제 요청의 처리 상태.
 *
 * <p>{@code PENDING}이면 새 주문의 생성·연결이 아직 확인되지 않았고 공고는 이전 조건과 이전 주문을 유지한다. {@code APPLIED}면
 * {@code paymentOrderId}가 새 주문이며 공고 조건이 이 요청의 조건으로 바뀌었다. 새 주문의 예치가 확인될 때까지 공고는 공개되지 않는다.
 * {@code REJECTED}면 새 주문이 연결되지 않았고 공고는 그대로다. {@code resolutionCode}는 거절 사유다(예: payment-service
 * {@code ORDER-409-001}).
 *
 * @param paymentJobVersion 새 주문의 결제용 버전. 재결제는 연결된 주문과 같은 버전이다.
 * @param amount            새 주문의 전체 예치 예정액(정수 KRW)
 */
public record JobPaymentChangeResponse(
        Long changeId,
        Long jobPostId,
        PaymentChangeType changeType,
        PaymentChangeStatus status,
        Long paymentJobVersion,
        Long amount,
        String currency,
        String paymentOrderId,
        String resolutionCode,
        LocalDateTime requestedAt,
        LocalDateTime resolvedAt
) {

    public static JobPaymentChangeResponse of(JobPaymentChangeRequest request, JobPaymentOrderCommand command) {
        return new JobPaymentChangeResponse(
                request.getId(),
                request.getJobPostId(),
                request.getChangeType(),
                request.getStatus(),
                command.getJobVersion(),
                command.getAmount(),
                command.getCurrency(),
                // 적용된 요청의 새 주문만 노출한다. 연결되지 않은 주문 ID로 결제를 시작하지 않게 한다.
                request.getStatus() == PaymentChangeStatus.APPLIED ? command.getOrderId() : null,
                request.getResolutionCode(),
                request.getCreatedAt(),
                request.getResolvedAt()
        );
    }
}
