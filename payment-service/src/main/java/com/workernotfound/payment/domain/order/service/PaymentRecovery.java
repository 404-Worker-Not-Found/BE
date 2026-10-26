package com.workernotfound.payment.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

// 결제 복구 설정(payment.recovery.enabled)이 켜져 있을 때만 등록된다.
// 정산 이벤트 설정도 스케줄링을 켜므로 빈 등록 자체를 같은 설정으로 막는다.
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRecovery {
  private final PaymentRecoveryService recoveries;

  @Scheduled(
      fixedDelayString = "${payment.recovery.interval:30s}",
      initialDelayString = "${payment.recovery.interval:30s}")
  public void recover() {
    recoveries.recover();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      name = "payment.recovery.enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Scheduling {}
}
