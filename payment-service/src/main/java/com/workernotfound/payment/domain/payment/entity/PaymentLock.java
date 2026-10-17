package com.workernotfound.payment.domain.payment.entity;

import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.global.exception.BusinessException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Table(name = "payment_locks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentLock {
  // TRANSFERRED: 잠금 금액이 정산으로 넘어가 정산이 금액을 소유한다. Saga 해제로 가용 잔액에 돌아가지 않는다.
  public enum Status { LOCKED, RELEASED, TRANSFERRED }
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false) private Long depositId;
  @Column(nullable = false) private Long matchingId;
  @Column(nullable = false) private Long jobPostId;
  @Column(nullable = false) private Long ownerMemberId;
  @Column(nullable = false) private Long workerMemberId;
  @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
  @Column(nullable = false, length = 3) private String currency;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
  @Column(nullable = false) private LocalDateTime createdAt;
  private LocalDateTime releasedAt;
  private LocalDateTime transferredAt;
  @Version private Long version;

  @Builder
  private PaymentLock(Long depositId, Long matchingId, Long jobPostId, Long ownerMemberId,
      Long workerMemberId, BigDecimal amount, String currency) {
    this.depositId = depositId;
    this.matchingId = matchingId;
    this.jobPostId = jobPostId;
    this.ownerMemberId = ownerMemberId;
    this.workerMemberId = workerMemberId;
    this.amount = amount;
    this.currency = currency;
    this.status = Status.LOCKED;
    this.createdAt = LocalDateTime.now();
  }

  public boolean release() {
    if (status == Status.TRANSFERRED)
      throw new BusinessException(PaymentErrorCode.LOCK_TRANSFERRED_TO_SETTLEMENT);
    if (status == Status.RELEASED) return false;
    status = Status.RELEASED;
    releasedAt = LocalDateTime.now();
    return true;
  }

  public void transferToSettlement() {
    if (status != Status.LOCKED) throw new IllegalStateException("잠금 상태에서만 정산으로 이관할 수 있습니다.");
    status = Status.TRANSFERRED;
    transferredAt = LocalDateTime.now();
  }

  public boolean isLocked() {
    return status == Status.LOCKED;
  }
}
