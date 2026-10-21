package com.workernotfound.payment.domain.refund.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 결제 복구 스케줄링(payment.recovery.enabled)이 켜져 있을 때만 실행된다.
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundRecovery {
  private static final int BATCH_SIZE = 20;
  private final RefundExecutionService executions;

  @Scheduled(
      fixedDelayString = "${payment.refund.interval:30s}",
      initialDelayString = "${payment.refund.interval:30s}")
  public void executeDue() {
    try {
      executions.executeDue(BATCH_SIZE);
    } catch (RuntimeException exception) {
      log.warn("환불 실행 대상 점유 실패: type={}", exception.getClass().getSimpleName());
    }
  }
}
