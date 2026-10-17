package com.workernotfound.payment.domain.payment.entity;

import static org.assertj.core.api.Assertions.*;

import com.workernotfound.payment.domain.payment.exception.PaymentErrorCode;
import com.workernotfound.payment.global.exception.BusinessException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentDepositTests {

  PaymentDeposit deposit(String amount) {
    return PaymentDeposit.builder().jobPostId(1L).ownerMemberId(20L).currency("KRW")
        .depositedAmount(new BigDecimal(amount)).build();
  }

  void assertBalance(PaymentDeposit deposit, String available, String locked, String reserved, String paid) {
    assertThat(deposit.availableAmount()).isEqualByComparingTo(available);
    assertThat(deposit.getLockedAmount()).isEqualByComparingTo(locked);
    assertThat(deposit.getSettlementReservedAmount()).isEqualByComparingTo(reserved);
    assertThat(deposit.getSettlementPaidAmount()).isEqualByComparingTo(paid);
    assertThat(deposit.availableAmount().add(deposit.getLockedAmount())
        .add(deposit.getSettlementReservedAmount()).add(deposit.getSettlementPaidAmount()))
        .isEqualByComparingTo(deposit.getDepositedAmount());
  }

  @Test
  void settlementReservationAndPayoutKeepTotalAndAvailableBalance() {
    PaymentDeposit deposit = deposit("100");
    deposit.reserve(20L, "KRW", new BigDecimal("60"));
    assertBalance(deposit, "40", "60", "0", "0");
    deposit.transferToSettlement(new BigDecimal("60"), new BigDecimal("60"));
    assertBalance(deposit, "40", "0", "60", "0");
    deposit.completeSettlementPayout(new BigDecimal("60"));
    assertBalance(deposit, "40", "0", "0", "60");
  }

  @Test
  void reservedAndPaidSettlementsAreNotAvailableForNewLocks() {
    PaymentDeposit deposit = deposit("100");
    deposit.reserve(20L, "KRW", new BigDecimal("60"));
    deposit.transferToSettlement(new BigDecimal("60"), new BigDecimal("60"));
    assertThatThrownBy(() -> deposit.reserve(20L, "KRW", new BigDecimal("41")))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.INSUFFICIENT_DEPOSIT));
    deposit.completeSettlementPayout(new BigDecimal("60"));
    assertThatThrownBy(() -> deposit.reserve(20L, "KRW", new BigDecimal("41")))
        .isInstanceOf(BusinessException.class);
    deposit.reserve(20L, "KRW", new BigDecimal("40"));
    assertBalance(deposit, "0", "40", "0", "60");
  }

  @Test
  void smallerSettlementReturnsDifferenceToAvailableBalance() {
    PaymentDeposit deposit = deposit("100");
    deposit.reserve(20L, "KRW", new BigDecimal("60"));
    deposit.transferToSettlement(new BigDecimal("60"), new BigDecimal("50"));
    assertBalance(deposit, "50", "0", "50", "0");
  }

  @Test
  void invariantViolationsDoNotChangeBalances() {
    PaymentDeposit deposit = deposit("100");
    deposit.reserve(20L, "KRW", new BigDecimal("60"));
    assertThatIllegalStateException()
        .isThrownBy(() -> deposit.transferToSettlement(new BigDecimal("61"), new BigDecimal("61")));
    assertThatIllegalStateException()
        .isThrownBy(() -> deposit.transferToSettlement(new BigDecimal("60"), new BigDecimal("61")));
    assertThatIllegalStateException()
        .isThrownBy(() -> deposit.transferToSettlement(new BigDecimal("60"), BigDecimal.ZERO));
    assertThatIllegalStateException()
        .isThrownBy(() -> deposit.completeSettlementPayout(BigDecimal.ONE));
    assertBalance(deposit, "40", "60", "0", "0");
    deposit.transferToSettlement(new BigDecimal("60"), new BigDecimal("60"));
    assertThatIllegalStateException()
        .isThrownBy(() -> deposit.completeSettlementPayout(new BigDecimal("61")));
    assertBalance(deposit, "40", "0", "60", "0");
  }

  @Test
  void lockReleaseRuleDependsOnStatus() {
    PaymentLock released = lock();
    assertThat(released.release()).isTrue();
    assertThat(released.release()).isFalse();
    assertThatIllegalStateException().isThrownBy(released::transferToSettlement);

    PaymentLock transferred = lock();
    transferred.transferToSettlement();
    assertThat(transferred.getStatus()).isEqualTo(PaymentLock.Status.TRANSFERRED);
    assertThat(transferred.getTransferredAt()).isNotNull();
    assertThatThrownBy(transferred::release).isInstanceOfSatisfying(BusinessException.class,
        exception -> assertThat(exception.getErrorCode())
            .isEqualTo(PaymentErrorCode.LOCK_TRANSFERRED_TO_SETTLEMENT));
    assertThatIllegalStateException().isThrownBy(transferred::transferToSettlement);
    assertThat(transferred.getStatus()).isEqualTo(PaymentLock.Status.TRANSFERRED);
  }

  PaymentLock lock() {
    return PaymentLock.builder().depositId(1L).matchingId(2L).jobPostId(1L).ownerMemberId(20L)
        .workerMemberId(30L).amount(new BigDecimal("60")).currency("KRW").build();
  }
}
