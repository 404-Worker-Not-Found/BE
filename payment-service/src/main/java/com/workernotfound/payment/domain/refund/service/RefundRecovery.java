package com.workernotfound.payment.domain.refund.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 결제 복구 스케줄링(payment.recovery.enabled)이 켜져 있을 때만 등록된다.
// 정산 이벤트 설정도 스케줄링을 켜므로 빈 등록 자체를 같은 설정으로 막는다.
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.recovery.enabled", havingValue = "true", matchIfMissing = true)
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
