package com.workernotfound.payment.domain.settlement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

/**
 * matching-service가 MatchConfirmed로 확정한 매칭의 정산 대상. 확정된 근무와 그 Saga 시도의 결제 잠금을 묶는다.
 *
 * <p>WorkCompleted에는 결제 ID와 금액이 없으므로 정산 예약은 이 대상의 잠금만 쓴다. 확정 사실은 바뀌지 않으므로 갱신하지 않는다.
 */
@Entity
@Table(name = "payment_settlement_targets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementTarget {
  @Id private Long matchingId;
  @Column(nullable = false, unique = true) private Long workId;
  @Column(nullable = false, unique = true) private Long paymentLockId;
  @Column(nullable = false) private Long jobPostId;
  @Column(nullable = false) private Long ownerMemberId;
  @Column(nullable = false) private Long workerMemberId;
  @Column(nullable = false, precision = 19, scale = 2) private BigDecimal lockedAmount;
  @Column(nullable = false, length = 3) private String currency;
  @Column(nullable = false) private Long matchingRevision;
  @Column(nullable = false, length = 128) private String sourceEventId;
  @Column(nullable = false) private LocalDateTime confirmedAt;
  @Column(nullable = false) private LocalDateTime createdAt;

  @Builder
  private SettlementTarget(Long matchingId, Long workId, Long paymentLockId, Long jobPostId,
      Long ownerMemberId, Long workerMemberId, BigDecimal lockedAmount, String currency,
      Long matchingRevision, String sourceEventId, LocalDateTime confirmedAt) {
    this.matchingId = matchingId;
    this.workId = workId;
    this.paymentLockId = paymentLockId;
    this.jobPostId = jobPostId;
    this.ownerMemberId = ownerMemberId;
    this.workerMemberId = workerMemberId;
    this.lockedAmount = lockedAmount;
    this.currency = currency;
    this.matchingRevision = matchingRevision;
    this.sourceEventId = sourceEventId;
    this.confirmedAt = confirmedAt;
    this.createdAt = LocalDateTime.now();
  }

  public boolean hasSameParticipants(Long jobPostId, Long ownerMemberId, Long workerMemberId) {
    return this.jobPostId.equals(jobPostId) && this.ownerMemberId.equals(ownerMemberId)
        && this.workerMemberId.equals(workerMemberId);
  }

  // 확정 사실(근무·잠금·참여자·금액)만 비교한다. 같은 확정의 재전달은 eventId·revision·시각이 달라도 같은 대상이다.
  public boolean hasSameConfirmation(Long workId, Long paymentLockId, Long jobPostId, Long ownerMemberId,
      Long workerMemberId, BigDecimal lockedAmount, String currency) {
    return this.workId.equals(workId) && this.paymentLockId.equals(paymentLockId)
        && hasSameParticipants(jobPostId, ownerMemberId, workerMemberId)
        && this.lockedAmount.compareTo(lockedAmount) == 0 && this.currency.equals(currency);
  }
}
