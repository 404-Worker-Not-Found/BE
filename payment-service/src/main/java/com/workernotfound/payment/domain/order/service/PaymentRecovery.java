package com.workernotfound.payment.domain.order.service;

import com.workernotfound.payment.domain.order.repository.*;
import com.workernotfound.payment.external.job.FundingJobClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecovery {
  private final PaymentOrderRepository orders;
  private final OrderApplicationService service;
  private final FundingNotificationRepository notifications;
  private final FundingJobClient job;

  @Scheduled(
      fixedDelayString = "${payment.recovery.interval:30s}",
      initialDelayString = "${payment.recovery.interval:30s}")
  public void recover() {
    for (String id : orders.findDueOrders()) {
      try {
        service.recover(id);
      } catch (RuntimeException exception) {
        log.warn("결제 조회 복구 실패: type={}", exception.getClass().getSimpleName());
      }
    }
    for (int i = 0; i < 20; i++) {
      var notification = notifications.claim();
      if (notification == null) break;
      try {
        job.deliver(notification);
        notifications.acknowledge(notification);
      } catch (RuntimeException exception) {
        notifications.retry(notification);
        log.warn("예치 상태 전달 실패: type={}", exception.getClass().getSimpleName());
      }
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      name = "payment.recovery.enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Scheduling {}
}
