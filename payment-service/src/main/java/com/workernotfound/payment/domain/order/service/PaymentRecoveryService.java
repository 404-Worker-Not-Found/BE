package com.workernotfound.payment.domain.order.service;

import com.workernotfound.payment.domain.order.repository.*;
import com.workernotfound.payment.external.job.FundingJobClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 결과를 다시 조회할 주문을 복구하고 밀린 예치 상태 전달을 재시도한다. 스케줄 실행은 PaymentRecovery가 맡는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRecoveryService {
  private final PaymentOrderRepository orders;
  private final OrderApplicationService service;
  private final FundingNotificationRepository notifications;
  private final FundingJobClient job;

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
}
