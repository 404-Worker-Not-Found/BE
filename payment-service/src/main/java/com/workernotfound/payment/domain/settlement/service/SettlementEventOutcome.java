package com.workernotfound.payment.domain.settlement.service;

/**
 * 정산 이벤트 하나의 처리 결과. duplicate는 같은 eventId의 수신 기록에서 결과를 다시 돌려준 경우다.
 */
public record SettlementEventOutcome(String result, Long settlementId, boolean isRejected, boolean isDuplicate) {}
