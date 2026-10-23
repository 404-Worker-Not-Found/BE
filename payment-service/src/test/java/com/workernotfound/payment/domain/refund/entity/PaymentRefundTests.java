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
    succeeded.succeed();
    assertThat(succeeded.getStatus()).isEqualTo(SUCCEEDED);
    assertThat(succeeded.getCompletedAt()).isNotNull();
    assertThatIllegalStateException().isThrownBy(succeeded::fail);
    assertThatIllegalStateException().isThrownBy(succeeded::succeed);

    PaymentRefund failed = refund();
    failed.fail();
    assertThat(failed.getStatus()).isEqualTo(FAILED);
    assertThatIllegalStateException().isThrownBy(failed::succeed);
  }
}
