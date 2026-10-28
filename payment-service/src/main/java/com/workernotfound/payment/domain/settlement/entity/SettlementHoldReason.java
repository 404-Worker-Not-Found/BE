package com.workernotfound.payment.domain.settlement.entity;

public enum SettlementHoldReason {
  // 예치 후 PG 취소·부분취소가 확인된 예치. 지급하면 취소된 결제로 근로자에게 지급하게 된다.
  FUNDING_BLOCKED,
  // 취소·노쇼 후 이미 예약된 정산의 지급 보류. 해제 조건은 후속 계약에서 정한다.
  WORK_TERMINATED
}
