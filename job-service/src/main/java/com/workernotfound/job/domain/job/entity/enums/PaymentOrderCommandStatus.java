package com.workernotfound.job.domain.job.entity.enums;

/**
 * 결제 주문 생성 명령의 처리 상태.
 *
 * <p>통신·검증 실패로는 종결하지 않는다. {@code SUCCEEDED}는 검증된 주문 ID를 공고에 연결한 경우뿐이며,
 * {@code SUPERSEDED}는 같은 공고에 더 늦게 발급된 명령이 있거나 공고가 더 이상 교체할 수 없는 상태여서 이 명령의 결과를 공고에
 * 연결하지 않은 경우다. {@code REJECTED}는 payment-service가 주문 교체를 확정적으로 거절해(409) 주문이 만들어지지 않은 경우이며,
 * 결제 조건 변경·재결제 명령에만 쓴다. 거절된 명령의 키는 다시 보내지 않고, 다음 시도는 새 명령·새 키로 한다.
 */
public enum PaymentOrderCommandStatus {
    PENDING,
    SUCCEEDED,
    SUPERSEDED,
    REJECTED
}
