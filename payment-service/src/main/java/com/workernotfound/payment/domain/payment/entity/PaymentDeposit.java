package com.workernotfound.payment.domain.payment.entity;

import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.global.exception.BusinessException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.*;

@Entity
@Table(name = "payment_deposits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentDeposit {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(nullable = false, unique = true) private Long jobPostId;
  @Column(nullable = false) private Long ownerMemberId;
  @Column(nullable = false, length = 3) private String currency;
  @Column(nullable = false, precision = 19, scale = 2) private BigDecimal depositedAmount;
  @Column(nullable = false, precision = 19, scale = 2) private BigDecimal lockedAmount;
  @Version private Long version;

  @Builder
  private PaymentDeposit(Long jobPostId, Long ownerMemberId, String currency, BigDecimal depositedAmount) {
    this.jobPostId = jobPostId;
    this.ownerMemberId = ownerMemberId;
    this.currency = currency;
    this.depositedAmount = depositedAmount;
    this.lockedAmount = BigDecimal.ZERO;
  }

  public void reserve(Long ownerId, String requestedCurrency, BigDecimal amount) {
    if (!ownerMemberId.equals(ownerId) || !currency.equals(requestedCurrency))
      throw new BusinessException(PaymentErrorCode.DEPOSIT_MISMATCH);
    if (depositedAmount.subtract(lockedAmount).compareTo(amount) < 0)
      throw new BusinessException(PaymentErrorCode.INSUFFICIENT_DEPOSIT);
    lockedAmount = lockedAmount.add(amount);
  }

  public void release(BigDecimal amount) {
    if (lockedAmount.compareTo(amount) < 0)
      throw new IllegalStateException("예치 잠금 잔액 불변식 위반");
    lockedAmount = lockedAmount.subtract(amount);
  }
}
