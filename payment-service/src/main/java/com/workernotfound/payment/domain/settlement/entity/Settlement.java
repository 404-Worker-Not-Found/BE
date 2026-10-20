package com.workernotfound.payment.domain.settlement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

/**
 * 근무 완료 사실로 만든 근로자 정산. 근무와 잠금은 각각 정산 하나만 가진다.
 *
 * <p>sourceAmount는 정산으로 넘어온 잠금 금액 스냅샷이다. 수수료·공제 정책이 확정되기 전에는 잠금 금액 전액을 정산 금액으로 예약한다.
 * workCompletedAt과 payableAt은 work-service 이벤트와 같은 근무 시간대의 로컬 시각이다.
 */
@Entity
@Table(name = "payment_settlements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false, unique = true) private Long workId;
  @Column(nullable = false, unique = true) private Long paymentLockId;
  @Column(nullable = false) private Long depositId;
  @Column(nullable = false) private Long matchingId;
  @Column(nullable = false) private Long jobPostId;
  @Column(nullable = false) private Long ownerMemberId;
  @Column(nullable = false) private Long workerMemberId;
  @Column(nullable = false, precision = 19, scale = 2) private BigDecimal sourceAmount;
  @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
  @Column(nullable = false, length = 3) private String currency;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private SettlementStatus status;
  @Enumerated(EnumType.STRING) @Column(length = 30) private SettlementHoldReason holdReason;
  @Column(nullable = false, length = 64) private String sourceEventId;
  @Column(nullable = false) private LocalDateTime workCompletedAt;
  @Column(nullable = false) private LocalDateTime payableAt;
  @Column(nullable = false) private LocalDateTime createdAt;
  private LocalDateTime paidAt;
  @Version private Long version;

  @Builder
  private Settlement(Long workId, Long paymentLockId, Long depositId, Long matchingId, Long jobPostId,
      Long ownerMemberId, Long workerMemberId, BigDecimal sourceAmount, String currency,
      String sourceEventId, LocalDateTime workCompletedAt, LocalDateTime payableAt) {
    this.workId = workId;
    this.paymentLockId = paymentLockId;
    this.depositId = depositId;
    this.matchingId = matchingId;
    this.jobPostId = jobPostId;
    this.ownerMemberId = ownerMemberId;
    this.workerMemberId = workerMemberId;
    this.sourceAmount = sourceAmount;
    this.amount = sourceAmount;
    this.currency = currency;
    this.sourceEventId = sourceEventId;
    this.workCompletedAt = workCompletedAt;
    this.payableAt = payableAt;
    this.status = SettlementStatus.RESERVED;
    this.createdAt = LocalDateTime.now();
  }

  public void hold(SettlementHoldReason reason) {
    transition(SettlementStatus.ON_HOLD);
    holdReason = reason;
  }

  public void resume() {
    transition(SettlementStatus.RESERVED);
    holdReason = null;
  }

  // 대기 기간이 지나기 전에는 지급을 요청할 수 없다.
  public void requestPayout(LocalDateTime now) {
    if (now.isBefore(payableAt)) throw new IllegalStateException("정산 지급 대기 기간이 지나지 않았습니다.");
    transition(SettlementStatus.PAYOUT_REQUESTED);
  }

  public void failPayout() {
    transition(SettlementStatus.PAYOUT_FAILED);
  }

  public void completePayout() {
    transition(SettlementStatus.PAID);
    paidAt = LocalDateTime.now();
  }

  public boolean hasSameCompletion(Long matchingId, Long jobPostId, Long ownerMemberId,
      Long workerMemberId, LocalDateTime workCompletedAt) {
    return this.matchingId.equals(matchingId) && this.jobPostId.equals(jobPostId)
        && this.ownerMemberId.equals(ownerMemberId) && this.workerMemberId.equals(workerMemberId)
        && this.workCompletedAt.equals(workCompletedAt);
  }

  private void transition(SettlementStatus next) {
    if (!status.canTransitionTo(next))
      throw new IllegalStateException("허용되지 않는 정산 상태 전이입니다: " + status + " -> " + next);
    status = next;
  }
}
