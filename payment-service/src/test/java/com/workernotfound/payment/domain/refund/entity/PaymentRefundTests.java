package com.workernotfound.payment.domain.refund.entity;

import static com.workernotfound.payment.domain.refund.entity.RefundStatus.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentRefundTests {

  PaymentRefund refund() {
    return PaymentRefund.builder().depositId(1L).orderId("order").jobPostId(2L).ownerMemberId(20L)
        .amount(new BigDecimal("1000")).currency("KRW").reason("미사용 예치금").build();
  }

  @Test
  void onlyRequestedRefundCanBeFinished() {
    Map<RefundStatus, Set<RefundStatus>> allowed = Map.of(
        REQUESTED, Set.of(SUCCEEDED, FAILED),
        SUCCEEDED, Set.of(),
        FAILED, Set.of());
    for (RefundStatus from : RefundStatus.values()) {
      for (RefundStatus to : RefundStatus.values()) {
        assertThat(from.canTransitionTo(to)).as(from + " -> " + to)
            .isEqualTo(allowed.get(from).contains(to));
      }
    }
  }

  @Test
  void newRefundIsRequestedUntilProviderResultIsConfirmed() {
    PaymentRefund refund = refund();
    assertThat(refund.getStatus()).isEqualTo(REQUESTED);
    assertThat(refund.getRequestedAt()).isNotNull();
    assertThat(refund.getRequestedAt().getNano() % 1_000).isZero();
    assertThat(refund.getCompletedAt()).isNull();
  }

  @Test
  void finishedRefundKeepsFirstResult() {
    PaymentRefund succeeded = refund();
    succeeded.succeed("tx-1");
    assertThat(succeeded.getStatus()).isEqualTo(SUCCEEDED);
    assertThat(succeeded.getProviderTransactionKey()).isEqualTo("tx-1");
    assertThat(succeeded.getCompletedAt()).isNotNull();
    assertThatIllegalStateException().isThrownBy(() -> succeeded.fail("NOT_CANCELABLE_AMOUNT"));
    assertThatIllegalStateException().isThrownBy(() -> succeeded.succeed(null));
    assertThatIllegalStateException().isThrownBy(() -> succeeded.requireReview("PROVIDER_MISMATCH"));

    PaymentRefund failed = refund();
    failed.fail("NOT_CANCELABLE_AMOUNT");
    assertThat(failed.getStatus()).isEqualTo(FAILED);
    assertThat(failed.getFailureCode()).isEqualTo("NOT_CANCELABLE_AMOUNT");
    assertThatIllegalStateException().isThrownBy(() -> failed.succeed(null));
  }

  @Test
  void failureNeedsProviderCodeAndReviewKeepsRefundRequested() {
    PaymentRefund refund = refund();
    assertThatIllegalArgumentException().isThrownBy(() -> refund.fail(" "));
    assertThat(refund.getStatus()).isEqualTo(REQUESTED);
    refund.requireReview("UNEXPLAINED_CANCELLATION");
    assertThat(refund.getStatus()).isEqualTo(REQUESTED);
    assertThat(refund.isReviewRequired()).isTrue();
    assertThat(refund.getFailureCode()).isEqualTo("UNEXPLAINED_CANCELLATION");
    assertThat(refund.getCompletedAt()).isNull();
  }
}
