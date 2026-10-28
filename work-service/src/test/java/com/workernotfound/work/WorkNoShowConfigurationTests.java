package com.workernotfound.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.workernotfound.work.domain.work.service.*;
import com.workernotfound.work.global.config.WorkNoShowConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkNoShowConfigurationTests {
  @ParameterizedTest
  @CsvSource({"gps,true,true", "custom,true,false", "gps,false,false"})
  void automaticNoShowOnlyRunsForEnabledGpsPolicy(String policy, boolean enabled, boolean expected) {
    new ApplicationContextRunner().withUserConfiguration(WorkNoShowConfiguration.class)
        .withBean(WorkNoShowReconciler.class, () -> mock(WorkNoShowReconciler.class))
        .withPropertyValues("work.attendance.policy-type=" + policy, "work.no-show.enabled=" + enabled,
            "work.no-show.initial-delay-ms=3600000")
        .run(context -> assertThat(context.getBeansOfType(WorkNoShowScheduler.class).isEmpty()).isEqualTo(!expected));
  }
}
