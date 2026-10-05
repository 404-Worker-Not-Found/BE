package com.workernotfound.work.global.config;

import com.workernotfound.work.domain.outbox.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(WorkOutboxProperties.class)
public class WorkOutboxConfiguration {
  @Configuration
  @RequiredArgsConstructor
  @ConditionalOnProperty(name = "work.outbox.enabled", havingValue = "true", matchIfMissing = true)
  static class Scheduler {
    private final WorkOutboxRelay relay;
    @Scheduled(fixedDelayString = "${work.outbox.poll-delay-ms:1000}",
        initialDelayString = "${work.outbox.initial-delay-ms:1000}")
    public void publish() { relay.relay(); }
  }
}
