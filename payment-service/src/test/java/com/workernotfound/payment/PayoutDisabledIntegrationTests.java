package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.workernotfound.payment.domain.payout.service.PayoutExecutionService;
import com.workernotfound.payment.domain.payout.service.PayoutScheduler;
import com.workernotfound.payment.external.payout.PayoutGateway;
import com.workernotfound.payment.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class PayoutDisabledIntegrationTests extends IntegrationTestSupport {
  @Autowired ApplicationContext context;

  @Test void defaultRuntimeHasNoPayoutExecutionOrFakeProvider() {
    assertThat(context.getBeansOfType(PayoutExecutionService.class)).isEmpty();
    assertThat(context.getBeansOfType(PayoutScheduler.class)).isEmpty();
    assertThat(context.getBeansOfType(PayoutGateway.class)).isEmpty();
  }
}
