package com.workernotfound.payment.domain.payment.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Table(name = "payment_locks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentLock {
  public enum Status { LOCKED, RELEASED }
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
    if (status == Status.RELEASED) return false;
    status = Status.RELEASED;
    releasedAt = LocalDateTime.now();
    return true;
  }
}
