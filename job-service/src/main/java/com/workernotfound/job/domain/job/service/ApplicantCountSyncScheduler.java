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
@ConditionalOnProperty(name = "job.applicant-count.sync.enabled", havingValue = "true", matchIfMissing = true)
public class ApplicantCountSyncScheduler {

    private final ApplicantCountSyncService syncService;

    @Scheduled(
            fixedDelayString = "${job.applicant-count.sync.interval}",
            initialDelayString = "${job.applicant-count.sync.initial-delay}"
    )
    public void syncApplicantCounts() {
        try {
            syncService.sync();
        } catch (RuntimeException exception) {
            log.warn("지원자 수 projection 적재 실행 실패: type={}", exception.getClass().getName());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "job.applicant-count.sync.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
    }
}
