package com.workernotfound.job.domain.job.entity.enums;

/**
 * 검증을 통과한 예치 상태 알림의 처리 결과. 모두 200 success로 응답하고 수신 기록에 남는다.
 * 연결 대기, 주문 불일치, 멱등 충돌처럼 거절한 알림은 결과로 저장하지 않는다.
 */
public enum FundingStatusResult {
    // 예치 확인을 반영하고 PAYMENT_PENDING 공고를 OPEN으로 공개했다.
    PUBLISHED,
    // 예치 확인을 반영했다. 이미 공개된(OPEN·MATCHING) 공고라 상태 전이는 없다. 차단돼 있었다면 차단만 해제한다.
    FUNDING_CONFIRMED,
    // 예치 확인을 반영했지만 공개할 수 없는 공고다(마감, 지원 마감·근무 시작 경과). 환불 검토 대상으로 남긴다.
    PUBLICATION_SKIPPED,
    // 예치 취소·검토 필요를 반영해 신규 지원 승인과 신규 자리 예약을 차단했다. 공고 상태는 바꾸지 않는다.
    FUNDING_BLOCKED,
    // 이 주문에 이미 같거나 더 높은 revision이 적용됐다. 예치 상태와 공고 상태를 바꾸지 않는다.
    STALE_REVISION,
    // 공고에 연결된 최신 주문이 아닌 이전 주문의 알림이다. 최신 주문의 예치 상태를 바꾸지 않는다.
    STALE_ORDER
}
