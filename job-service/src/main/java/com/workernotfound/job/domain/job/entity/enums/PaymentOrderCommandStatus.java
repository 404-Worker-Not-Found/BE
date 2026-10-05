package com.workernotfound.job.domain.job.entity.enums;

/**
 * 결제 주문 생성 명령의 처리 상태.
 *
 * <p>통신·검증 실패로는 종결하지 않는다. {@code SUCCEEDED}는 검증된 주문 ID를 공고에 연결한 경우뿐이며,
 * {@code SUPERSEDED}는 같은 공고에 더 늦게 발급된 명령이 있어 이 명령의 결과를 공고에 연결하지 않은 경우다.
 */
public enum PaymentOrderCommandStatus {
    PENDING,
    SUCCEEDED,
    SUPERSEDED
}
