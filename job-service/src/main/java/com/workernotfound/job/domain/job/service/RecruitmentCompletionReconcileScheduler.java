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
        name = "job.recruitment-completion.reconcile.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RecruitmentCompletionReconcileScheduler {

    private final RecruitmentCompletionReconciler reconciler;

    @Scheduled(
            fixedDelayString = "${job.recruitment-completion.reconcile.interval}",
            initialDelayString = "${job.recruitment-completion.reconcile.initial-delay}"
    )
    public void reconcileFilledJobs() {
        try {
            reconciler.reconcile();
        } catch (RuntimeException exception) {
            log.warn("모집 완료 복구 실행 실패: type={}", exception.getClass().getName());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(
            name = "job.recruitment-completion.reconcile.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    static class Scheduling {
    }
}
