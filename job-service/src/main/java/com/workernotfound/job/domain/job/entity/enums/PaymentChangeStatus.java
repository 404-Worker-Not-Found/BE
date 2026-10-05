package com.workernotfound.job.domain.job.entity.enums;

/**
 * 결제 조건 변경·재결제 요청의 처리 상태.
 *
 * <p>{@code PENDING}은 새 주문의 생성·연결이 확인되지 않은 상태로, 결과 불명도 여기에 머문다. 공고의 현재 조건과 주문 연결은
 * {@code APPLIED}가 될 때 같은 트랜잭션에서만 바뀐다. {@code REJECTED}는 새 주문이 연결되지 않은 종료 상태이며 공고는 그대로다.
 */
public enum PaymentChangeStatus {
    PENDING,
    APPLIED,
    REJECTED
}
