package com.workernotfound.job.domain.job.entity;

/**
 * 형식 검증을 통과한 예치 상태 알림. 금액은 소수 표기와 관계없이 정수 KRW로 정규화한 값이다.
 * 멱등 재요청과 같은 주문·revision의 중복 판정은 경로의 공고 ID와 이 필드 전체를 비교한다.
 */
public record FundingStatusNotification(
        String orderId,
        Long jobVersion,
        Long ownerMemberId,
        Long amount,
        String currency,
        Long fundingRevision,
        boolean funded
) {
}
