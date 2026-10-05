package com.workernotfound.job.domain.job.dto.response;

import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.entity.JobPost;
import com.workernotfound.job.domain.job.entity.enums.JobStatus;

/**
 * 점주 본인 공고의 결제 주문 생성 상태.
 *
 * <p>{@code orderCreationStatus}가 {@code PENDING}이면 주문 생성 요청이 아직 완료되지 않은 것이며 {@code paymentOrderId}는 null이다.
 * {@code CREATED}가 되면 payment-service에서 검증된 주문 ID가 채워진다. 결제 진행·예치 완료 여부는 payment-service 주문 조회로 확인하고,
 * 공고 공개 여부는 {@code jobStatus}로 확인한다. 주문 생성만으로 공고가 공개되지 않는다.
 *
 * @param paymentJobVersion 주문 금액을 계산한 결제용 공고 버전. 현재 공고 버전과 다를 수 있다.
 * @param amount            전체 예치 예정액(정수 KRW)
 * @param latestChange      가장 최근의 결제 조건 변경·재결제 요청. 없으면 null. {@code PENDING}인 동안 위 필드는 이전 주문이다.
 */
public record JobPaymentOrderResponse(
        Long jobPostId,
        JobStatus jobStatus,
        OrderCreationStatus orderCreationStatus,
        String paymentOrderId,
        Long paymentJobVersion,
        Long amount,
        String currency,
        JobPaymentChangeResponse latestChange
) {

    public enum OrderCreationStatus {
        // 결제 대기 생성 이전에 만들어져 주문 생성 명령이 없는 공고
        NOT_REQUESTED,
        // 주문 생성 명령이 저장됐고 payment-service 응답을 아직 검증·연결하지 못했다.
        PENDING,
        // 검증된 주문 ID가 공고에 연결됐다.
        CREATED
    }

    public static JobPaymentOrderResponse linked(JobPost jobPost, JobPaymentChangeResponse latestChange) {
        return new JobPaymentOrderResponse(
                jobPost.getId(),
                jobPost.getStatus(),
                OrderCreationStatus.CREATED,
                jobPost.getPaymentOrderId(),
                jobPost.getPaymentJobVersion(),
                jobPost.getPaymentAmount(),
                jobPost.getPaymentCurrency(),
                latestChange
        );
    }

    public static JobPaymentOrderResponse pending(JobPost jobPost, JobPaymentOrderCommand command) {
        return new JobPaymentOrderResponse(
                jobPost.getId(),
                jobPost.getStatus(),
                OrderCreationStatus.PENDING,
                null,
                command.getJobVersion(),
                command.getAmount(),
                command.getCurrency(),
                null
        );
    }

    public static JobPaymentOrderResponse notRequested(JobPost jobPost) {
        return new JobPaymentOrderResponse(
                jobPost.getId(), jobPost.getStatus(), OrderCreationStatus.NOT_REQUESTED, null, null, null, null, null);
    }
}
