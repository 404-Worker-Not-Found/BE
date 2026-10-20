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
        name = "job.application-admission-expiry.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class JobApplicationAdmissionExpiryScheduler {

    private final JobApplicationAdmissionExpiryReconciler reconciler;

    @Scheduled(
            fixedDelayString = "${job.application-admission-expiry.interval}",
            initialDelayString = "${job.application-admission-expiry.initial-delay}"
    )
    public void expireOverdueAdmissions() {
        try {
            reconciler.reconcile();
        } catch (RuntimeException exception) {
            log.warn("지원 접수 승인 만료 처리 실행 실패: type={}", exception.getClass().getName());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(
            name = "job.application-admission-expiry.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    static class Scheduling {
    }
}
