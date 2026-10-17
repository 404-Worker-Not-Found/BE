package com.workernotfound.payment.domain.settlement.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

/**
 * 정산 대상 기록에 쓰는 MatchConfirmed v1의 확정 사실. matching-service가 확정한 근무와 결제 잠금, 잠금 금액을 담는다.
 *
 * <p>workId·paymentLockId는 발행 형식의 숫자 문자열을 해석한 값이며 해석할 수 없으면 null이다. confirmedAt은 마이크로초로 자른다.
 */
public record MatchConfirmation(String eventId, Long matchingId, Long revision, Long workId,
    Long paymentLockId, Long jobPostId, Long ownerMemberId, Long workerMemberId,
    BigDecimal lockedAmount, String currency, LocalDateTime confirmedAt) {

  public MatchConfirmation {
    confirmedAt = confirmedAt == null ? null : confirmedAt.truncatedTo(ChronoUnit.MICROS);
  }

  public boolean isComplete() {
    return eventId != null && !eventId.isBlank() && eventId.length() <= 128 && confirmedAt != null
        && lockedAmount != null && lockedAmount.signum() > 0 && currency != null && !currency.isBlank()
        && Stream.of(matchingId, revision, workId, paymentLockId, jobPostId, ownerMemberId, workerMemberId)
            .allMatch(id -> id != null && id > 0);
  }
}
