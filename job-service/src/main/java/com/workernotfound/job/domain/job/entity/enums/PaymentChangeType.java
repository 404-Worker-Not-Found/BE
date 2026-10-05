package com.workernotfound.job.domain.job.entity.enums;

/**
 * 점주의 결제 주문 교체 요청 종류.
 *
 * <p>둘 다 새 멱등 키와 다음 순번의 주문 생성 명령을 발급하는 새 결제 시도다. 결제용 버전만 다르다.
 */
public enum PaymentChangeType {
    // 결제 조건(근무 일시·급여·모집 인원·지원 마감) 변경. 다음 결제용 버전으로 새 주문을 만든다.
    TERMS_CHANGE,
    // 같은 조건의 재결제. 연결된 주문과 같은 결제용 버전·금액으로 새 주문을 만든다. payment-service는 FAILED 주문만 대체한다.
    REPAYMENT
}
