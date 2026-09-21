package com.workernotfound.payment.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InternalApiPropertiesTests {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void missingSecretFailsStartup() {
    runner.run(context -> assertThat(context.getStartupFailure())
        .hasRootCauseInstanceOf(BindValidationException.class));
  }

  @Test
  void emptyAndWhitespaceSecretsFailStartup() {
    for (String secret : new String[] {"", "   "}) {
      runner.withPropertyValues("payment.internal.secret=" + secret)
          .run(context -> assertThat(context.getStartupFailure())
              .hasRootCauseInstanceOf(BindValidationException.class));
    }
  }

  @Test
  void configuredSecretStartsSuccessfully() {
    runner.withPropertyValues("payment.internal.secret=payment-test-internal-secret")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(InternalApiProperties.class).secret())
              .isEqualTo("payment-test-internal-secret");
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(InternalApiProperties.class)
  static class PropertiesConfiguration {}
}
