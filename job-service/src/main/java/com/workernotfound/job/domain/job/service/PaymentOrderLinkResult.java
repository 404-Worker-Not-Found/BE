package com.workernotfound.job.domain.job.service;

// 검증된 주문 생성 결과를 기록한 결과
public enum PaymentOrderLinkResult {
    // 명령을 성공으로 기록하고 주문을 공고에 연결했다.
    LINKED,
    // 더 늦게 발급된 명령이 있어 이 주문을 공고에 연결하지 않고 명령을 종료했다.
    SUPERSEDED,
    // 실행권을 잃었거나 명령이 이미 처리되어 아무것도 바꾸지 않았다.
    LEASE_LOST,
    // 결제 변경 명령의 주문이 만들어졌지만 공고가 더 이상 교체할 수 없는 상태여서 연결하지 않고 종료했다. 운영 확인 대상이다.
    JOB_STATE_CHANGED
}
