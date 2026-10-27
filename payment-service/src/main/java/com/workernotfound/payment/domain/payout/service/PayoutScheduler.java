package com.workernotfound.payment.domain.payout.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.payout.enabled", havingValue = "true")
public class PayoutScheduler {
  private final PayoutExecutionService execution;

  @Scheduled(fixedDelayString = "${payment.payout.poll-interval:30s}", initialDelayString = "${payment.payout.initial-delay:30s}")
  public void execute() { execution.executeDue(100); }
}
