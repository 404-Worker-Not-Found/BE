package com.workernotfound.payment.domain.settlement.entity;

import static com.workernotfound.payment.domain.settlement.entity.SettlementStatus.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class SettlementTests {
  static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 10, 18, 18, 0);
  static final LocalDateTime PAYABLE_AT = COMPLETED_AT.plusHours(24);

  Settlement settlement() {
    return Settlement.builder().workId(1L).paymentLockId(2L).depositId(3L).matchingId(4L).jobPostId(5L)
        .ownerMemberId(20L).workerMemberId(30L).sourceAmount(new BigDecimal("12000")).currency("KRW")
        .sourceEventId("event").workCompletedAt(COMPLETED_AT).payableAt(PAYABLE_AT).build();
  }

  @Test
  void allowedTransitionsMatchSettlementLifecycle() {
    Map<SettlementStatus, Set<SettlementStatus>> allowed = Map.of(
        RESERVED, Set.of(ON_HOLD, PAYOUT_REQUESTED),
        ON_HOLD, Set.of(RESERVED),
        PAYOUT_REQUESTED, Set.of(PAID, PAYOUT_FAILED),
        PAYOUT_FAILED, Set.of(PAYOUT_REQUESTED, ON_HOLD),
        PAID, Set.of());
    for (SettlementStatus from : SettlementStatus.values()) {
      for (SettlementStatus to : SettlementStatus.values()) {
        assertThat(from.canTransitionTo(to)).as(from + " -> " + to)
            .isEqualTo(allowed.get(from).contains(to));
      }
    }
  }

  @Test
  void newSettlementReservesFullSourceAmount() {
    Settlement settlement = settlement();
    assertThat(settlement.getStatus()).isEqualTo(RESERVED);
    assertThat(settlement.getAmount()).isEqualByComparingTo(settlement.getSourceAmount());
    assertThat(settlement.getHoldReason()).isNull();
    assertThat(settlement.getPaidAt()).isNull();
  }

  @Test
  void holdAndResumeKeepReasonOnlyWhileHeld() {
    Settlement settlement = settlement();
    settlement.hold(SettlementHoldReason.FUNDING_BLOCKED);
    assertThat(settlement.getStatus()).isEqualTo(ON_HOLD);
    assertThat(settlement.getHoldReason()).isEqualTo(SettlementHoldReason.FUNDING_BLOCKED);
    assertThatIllegalStateException().isThrownBy(() -> settlement.requestPayout(PAYABLE_AT));
    settlement.resume();
    assertThat(settlement.getStatus()).isEqualTo(RESERVED);
    assertThat(settlement.getHoldReason()).isNull();
  }

  @Test
  void payoutCannotBeRequestedBeforeWaitingPeriod() {
    Settlement settlement = settlement();
    assertThatIllegalStateException().isThrownBy(() -> settlement.requestPayout(PAYABLE_AT.minusNanos(1000)));
    assertThat(settlement.getStatus()).isEqualTo(RESERVED);
    settlement.requestPayout(PAYABLE_AT);
    assertThat(settlement.getStatus()).isEqualTo(PAYOUT_REQUESTED);
  }

  @Test
  void requestedPayoutMustResolveBeforeHoldOrRetry() {
    Settlement settlement = settlement();
    settlement.requestPayout(PAYABLE_AT);
    assertThatIllegalStateException().isThrownBy(() -> settlement.hold(SettlementHoldReason.FUNDING_BLOCKED));
    assertThatIllegalStateException().isThrownBy(() -> settlement.requestPayout(PAYABLE_AT));
    settlement.failPayout();
    settlement.requestPayout(PAYABLE_AT);
    settlement.completePayout();
    assertThat(settlement.getStatus()).isEqualTo(PAID);
    assertThat(settlement.getPaidAt()).isNotNull();
  }

  @Test
  void paidSettlementIsTerminal() {
    Settlement settlement = settlement();
    settlement.requestPayout(PAYABLE_AT);
    settlement.completePayout();
    assertThatIllegalStateException().isThrownBy(settlement::failPayout);
    assertThatIllegalStateException().isThrownBy(settlement::completePayout);
    assertThatIllegalStateException().isThrownBy(() -> settlement.hold(SettlementHoldReason.FUNDING_BLOCKED));
    assertThatIllegalStateException().isThrownBy(() -> settlement.requestPayout(PAYABLE_AT));
    assertThat(settlement.getStatus()).isEqualTo(PAID);
  }

  @Test
  void sameCompletionComparesBusinessReferencesAndCompletionTime() {
    Settlement settlement = settlement();
    assertThat(settlement.hasSameCompletion(4L, 5L, 20L, 30L, COMPLETED_AT)).isTrue();
    assertThat(settlement.hasSameCompletion(9L, 5L, 20L, 30L, COMPLETED_AT)).isFalse();
    assertThat(settlement.hasSameCompletion(4L, 5L, 20L, 31L, COMPLETED_AT)).isFalse();
    assertThat(settlement.hasSameCompletion(4L, 5L, 20L, 30L, COMPLETED_AT.plusSeconds(1))).isFalse();
  }
}
