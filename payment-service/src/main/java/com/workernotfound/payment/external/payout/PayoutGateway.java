package com.workernotfound.payment.external.payout;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

/** 실제 PG 계약을 구현한 어댑터만 등록한다. 운영용 성공 대역은 제공하지 않는다. */
public interface PayoutGateway {
  String resolveRecipient(long workerMemberId);
  // 어댑터는 reference를 업무 참조와 PG 멱등 키로 유지하고 조회 결과의 원본 값을 반환한다.
  Result request(Request request);
  Optional<Result> lookup(String reference);

  record Request(String reference, String recipient, BigDecimal amount, String currency) {}
  enum Status { PENDING, SUCCEEDED, FAILED }
  record Result(String reference, String recipient, BigDecimal amount, String currency,
      String providerId, Status status, OffsetDateTime paidAt) {}
}
