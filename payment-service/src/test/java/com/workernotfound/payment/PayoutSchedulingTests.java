package com.workernotfound.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.workernotfound.payment.domain.payout.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PayoutSchedulingTests {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(PayoutScheduler.class)
      .withBean(PayoutExecutionService.class, () -> mock(PayoutExecutionService.class))
      .withPropertyValues("payment.payout.initial-delay=1h");

  @Test void enabledPayoutOwnsSchedulingWithoutOtherRecoveryOrEventConfiguration() {
    runner.withPropertyValues("payment.payout.enabled=true").run(context -> {
      assertThat(context).hasNotFailed().hasSingleBean(PayoutScheduler.class);
      assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
    });
  }

  @Test void disabledOrDefaultPayoutDoesNotRegisterScheduler() {
    for (boolean explicit : new boolean[]{false, true}) {
      var contextRunner = explicit ? runner.withPropertyValues("payment.payout.enabled=false") : runner;
      contextRunner.run(context -> {
        assertThat(context).hasNotFailed().doesNotHaveBean(PayoutScheduler.class);
        assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
      });
    }
  }
}
