package com.workernotfound.job.domain.job.port;

import java.time.Duration;

/**
 * payment-service에 공고 예치 결제 주문 생성을 요청하는 포트.
 *
 * <p>정상 반환은 상대가 공통 응답 {@code success=true}로 주문을 돌려주고, 그 주문의 공고 ID·결제용 버전·금액·통화가 요청 스냅샷과
 * 같으며, 주문 상태가 연결 가능한 상태일 때뿐이다. 실패 응답, 계약과 다른 응답, 통신 실패(연결 오류, 제한시간 초과, 교환 취소·중단)는
 * {@link com.workernotfound.job.domain.job.exception.PaymentOrderCreationException}으로 알린다. 구현 내부의 예상하지 못한 오류는
 * 이 예외로 바꾸지 않고 다른 런타임 예외로 그대로 전파한다.
 */
public interface PaymentOrderCreator {

    CreatedPaymentOrder createOrder(PaymentOrderRequest request);

    /**
     * 한 번의 호출에 실제로 강제되는 전체 제한시간. 연결 시작부터 응답 본문 수신 완료까지를 포함한다.
     * 실행권 길이는 이 값을 기준으로 검증한다.
     */
    Duration maxCallDuration();

    // 저장된 명령 스냅샷 그대로 보내는 요청. 재시도마다 같은 값이다.
    record PaymentOrderRequest(
            String idempotencyKey,
            Long jobPostId,
            Long jobVersion,
            Long ownerMemberId,
            Long amount,
            String currency
    ) {
    }

    /**
     * 검증을 통과한 주문.
     *
     * @param orderStatus 응답 시점의 payment-service 주문 상태. 멱등 재요청이면 이미 결제가 진행된 상태일 수 있다.
     */
    record CreatedPaymentOrder(String orderId, String orderStatus) {
    }
}
