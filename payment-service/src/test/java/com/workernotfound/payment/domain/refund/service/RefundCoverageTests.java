package com.workernotfound.payment.domain.refund.service;

import static com.workernotfound.payment.domain.refund.service.RefundCoverage.*;
import static org.assertj.core.api.Assertions.*;

import com.workernotfound.payment.external.toss.TossPayment;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RefundCoverageTests {
  BigDecimal amount(String value) {
    return new BigDecimal(value);
  }

  @Test
  void providerCanceledTotalDecidesWhetherThisRefundWasApplied() {
    assertThat(of(amount("300"), amount("300"), amount("200"))).isEqualTo(NOT_APPLIED);
    assertThat(of(amount("500.00"), amount("300"), amount("200"))).isEqualTo(APPLIED);
    assertThat(of(amount("0"), amount("0"), amount("200"))).isEqualTo(NOT_APPLIED);
    // 이쪽 장부보다 많거나 적게 취소된 결제는 이 환불로 설명할 수 없다.
    assertThat(of(amount("600"), amount("300"), amount("200"))).isEqualTo(UNEXPLAINED);
    assertThat(of(amount("100"), amount("300"), amount("200"))).isEqualTo(UNEXPLAINED);
  }

  @Test
  void canceledAmountNeedsTotalAndBalance() {
    assertThat(canceledAmount(new TossPayment("k", "o", "PARTIAL_CANCELED", "KRW", "카드",
        amount("1000"), amount("700"), null))).isEqualByComparingTo("300");
    assertThat(canceledAmount(new TossPayment("k", "o", "CANCELED", "KRW", "카드",
        amount("1000"), null, null))).isNull();
  }
}
