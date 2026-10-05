package com.workernotfound.job.domain.job.entity.enums;

// 예치 확인이 환불 검토 대상이 된 사유. 공개 생략 사유와 이전 주문 예치를 구분한다.
public enum RefundReviewReason {
    // 공고에 연결된 최신 주문이 아닌 이전 주문의 예치 확인
    STALE_ORDER,
    JOB_CLOSED,
    APPLICATION_DEADLINE_PASSED,
    WORK_STARTED;

    public static RefundReviewReason of(FundingStatusResult result, FundingSkipReason skipReason) {
        if (result == FundingStatusResult.STALE_ORDER) {
            return STALE_ORDER;
        }
        if (result == FundingStatusResult.PUBLICATION_SKIPPED && skipReason != null) {
            return valueOf(skipReason.name());
        }
        throw new IllegalArgumentException("환불 검토 사유가 아닌 처리 결과입니다: " + result);
    }
}
