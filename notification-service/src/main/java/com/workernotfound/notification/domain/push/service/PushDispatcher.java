package com.workernotfound.notification.domain.push.service;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "notification.push.enabled", havingValue = "true")
public class PushDispatcher {
  private final PushDeliveryStore store;
  private final PushGateway gateway;

  @Scheduled(fixedDelayString = "${notification.push.delay-ms:5000}",
      initialDelayString = "${notification.push.initial-delay-ms:10000}", scheduler = "pushTaskScheduler")
  public void dispatch() {
    for (int index = 0; index < 20; index++) {
      try {
        store.claim().ifPresent(this::deliver);
      } catch (RuntimeException failure) {
        log.warn("푸시 작업 처리 실패: type={}", failure.getClass().getSimpleName());
      }
    }
  }

  private void deliver(PushDispatch dispatch) {
    PushResult result;
    try {
      result = gateway.send(dispatch);
    } catch (RuntimeException failure) {
      // Remote exceptions can contain registration tokens or provider response bodies.
      log.warn("푸시 발송 실패: deliveryId={}, type={}", dispatch.id(), failure.getClass().getSimpleName());
      result = new PushResult(PushResult.Outcome.RETRY, "GATEWAY_FAILURE", Duration.ofSeconds(30));
    }
    if (result.outcome() != PushResult.Outcome.ACCEPTED)
      log.warn("푸시 전송 결과: deliveryId={}, attempt={}, outcome={}, code={}",
          dispatch.id(), dispatch.attempt(), result.outcome(), result.code());
    store.complete(dispatch, result);
  }
}
