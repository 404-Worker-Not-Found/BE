package com.workernotfound.payment.external.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.workernotfound.payment.domain.settlement.service.WorkTerminationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

class WorkTerminationConfigurationTests {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(SettlementEventConfig.class, WorkTerminationEventConsumer.class)
      .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
      .withBean(WorkTerminationEventParser.class, () -> mock(WorkTerminationEventParser.class))
      .withBean(WorkTerminationService.class, () -> mock(WorkTerminationService.class))
      .withPropertyValues("payment.settlement-events.matching-stream=matching:events",
          "payment.settlement-events.work-stream=work:events", "payment.settlement-events.batch-size=10",
          "payment.settlement-events.poll-interval=1s", "payment.work-termination.initial-delay-ms=3600000");

  @Test void flagCombinationsStartAndDisableEitherDependency() {
    for (boolean settlement : new boolean[]{false,true}) {
      for (boolean termination : new boolean[]{false,true}) {
        runner.withPropertyValues("payment.settlement-events.enabled="+settlement,
            "payment.work-termination.enabled="+termination).run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBeansOfType(WorkTerminationEventConsumer.class))
              .hasSize(settlement && termination ? 1 : 0);
        });
      }
    }
  }

  @Test void omittedFlagsKeepDefaultConsumerEnabled() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(WorkTerminationEventConsumer.class);
    });
  }
}
