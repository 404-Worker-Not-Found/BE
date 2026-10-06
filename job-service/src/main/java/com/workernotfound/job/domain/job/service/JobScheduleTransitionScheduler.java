package com.workernotfound.job.domain.job.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "job.schedule-transition.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class JobScheduleTransitionScheduler {

    private final JobScheduleTransitionReconciler reconciler;

    @Scheduled(
            fixedDelayString = "${job.schedule-transition.interval}",
            initialDelayString = "${job.schedule-transition.initial-delay}"
    )
    public void applyDueTransitions() {
        try {
            reconciler.reconcile();
        } catch (RuntimeException exception) {
            log.warn("공고 자동 상태 전이 실행 실패: type={}", exception.getClass().getName());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(
            name = "job.schedule-transition.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    static class Scheduling {
    }
}
