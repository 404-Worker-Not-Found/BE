package com.workernotfound.payment.external.payout;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 실제 PG 계약을 구현한 어댑터만 등록한다. 운영용 성공 대역은 제공하지 않는다.
 * request/lookup의 전체 타임아웃은 payment.payout.lease-duration보다 짧아야 한다.
 * 임대 만료 뒤의 결과는 기존 참조 조회로 확인하며, 조회 부재를 근거로 재송금하지 않는다.
 */
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
