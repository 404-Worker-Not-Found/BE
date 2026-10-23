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
  @Column(nullable = false, precision = 19, scale = 2) private BigDecimal settlementReservedAmount;
  @Column(nullable = false, precision = 19, scale = 2) private BigDecimal settlementPaidAmount;
  @Column(nullable = false, precision = 19, scale = 2) private BigDecimal refundReservedAmount;
  @Column(nullable = false, precision = 19, scale = 2) private BigDecimal refundedAmount;
  @Column(nullable = false) private boolean fundingBlocked;
  @Version private Long version;

  @Builder
  private PaymentDeposit(Long jobPostId, Long ownerMemberId, String currency, BigDecimal depositedAmount) {
    this.jobPostId = jobPostId;
    this.ownerMemberId = ownerMemberId;
    this.currency = currency;
    this.depositedAmount = depositedAmount;
    this.lockedAmount = BigDecimal.ZERO;
    this.settlementReservedAmount = BigDecimal.ZERO;
    this.settlementPaidAmount = BigDecimal.ZERO;
    this.refundReservedAmount = BigDecimal.ZERO;
    this.refundedAmount = BigDecimal.ZERO;
  }

  // 예치 총액 = 가용 + 잠금 + 정산 예약 + 지급 완료 + 환불 예약 + 환불 완료.
  public BigDecimal availableAmount() {
    return depositedAmount.subtract(lockedAmount).subtract(settlementReservedAmount)
        .subtract(settlementPaidAmount).subtract(refundReservedAmount).subtract(refundedAmount);
  }

  public void reserve(Long ownerId, String requestedCurrency, BigDecimal amount) {
    requireAvailable(ownerId, requestedCurrency, amount);
    lockedAmount = lockedAmount.add(amount);
  }

  // 환불은 가용 잔액에서만 예약한다. 잠금·정산 금액은 환불과 동시에 쓰이지 않는다.
  public void reserveRefund(Long ownerId, String requestedCurrency, BigDecimal amount) {
    requireAvailable(ownerId, requestedCurrency, amount);
    refundReservedAmount = refundReservedAmount.add(amount);
  }

  public void completeRefund(BigDecimal amount) {
    requireRefundReserved(amount);
    refundReservedAmount = refundReservedAmount.subtract(amount);
    refundedAmount = refundedAmount.add(amount);
  }

  // PG가 환불을 확정적으로 거절했을 때만 예약 금액을 가용 잔액으로 돌린다.
  public void cancelRefund(BigDecimal amount) {
    requireRefundReserved(amount);
    refundReservedAmount = refundReservedAmount.subtract(amount);
  }

  private void requireAvailable(Long ownerId, String requestedCurrency, BigDecimal amount) {
    if (fundingBlocked) throw new BusinessException(PaymentErrorCode.FUNDING_BLOCKED);
    if (!ownerMemberId.equals(ownerId) || !currency.equals(requestedCurrency))
      throw new BusinessException(PaymentErrorCode.DEPOSIT_MISMATCH);
    if (availableAmount().compareTo(amount) < 0)
      throw new BusinessException(PaymentErrorCode.INSUFFICIENT_DEPOSIT);
  }

  private void requireRefundReserved(BigDecimal amount) {
    if (amount.signum() <= 0 || refundReservedAmount.compareTo(amount) < 0)
      throw new IllegalStateException("예치 환불 예약 불변식 위반");
  }

  public void release(BigDecimal amount) {
    if (lockedAmount.compareTo(amount) < 0)
      throw new IllegalStateException("예치 잠금 잔액 불변식 위반");
    lockedAmount = lockedAmount.subtract(amount);
  }

  // 잠금 금액을 정산 예약으로 옮긴다. 정산 금액이 잠금 금액보다 작으면 차액은 가용 잔액으로 돌아간다.
  public void transferToSettlement(BigDecimal lockAmount, BigDecimal settlementAmount) {
    if (lockedAmount.compareTo(lockAmount) < 0 || settlementAmount.signum() <= 0
        || settlementAmount.compareTo(lockAmount) > 0)
      throw new IllegalStateException("예치 정산 예약 불변식 위반");
    lockedAmount = lockedAmount.subtract(lockAmount);
    settlementReservedAmount = settlementReservedAmount.add(settlementAmount);
  }

  public void completeSettlementPayout(BigDecimal amount) {
    if (amount.signum() <= 0 || settlementReservedAmount.compareTo(amount) < 0)
      throw new IllegalStateException("예치 정산 지급 불변식 위반");
    settlementReservedAmount = settlementReservedAmount.subtract(amount);
    settlementPaidAmount = settlementPaidAmount.add(amount);
  }
}
