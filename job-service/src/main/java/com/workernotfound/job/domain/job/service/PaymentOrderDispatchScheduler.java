package com.workernotfound.job.domain.job.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 커밋 후 즉시 전송 여부와 관계없이 DB의 미완료 결제 주문 생성 명령을 주기적으로 찾아 전송한다.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "job.payment-order.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentOrderDispatchScheduler {

    private final PaymentOrderDispatcher dispatcher;

    @Scheduled(
            fixedDelayString = "${job.payment-order.dispatch-interval}",
            initialDelayString = "${job.payment-order.dispatch-interval}"
    )
    public void dispatchDueCommands() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException exception) {
            log.warn("결제 주문 생성 명령 전송 실행 실패: types={}", ExceptionTypeChain.describe(exception));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "job.payment-order.dispatch-enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
    }
}
