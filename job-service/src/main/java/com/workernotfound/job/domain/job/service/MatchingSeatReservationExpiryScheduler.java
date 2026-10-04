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
public class MatchingSeatReservationExpiryScheduler {

    private final MatchingSeatReservationExpiryService expiryService;

    @Scheduled(
            fixedDelayString = "${job.matching-seat-reservation.expiry-sweep-interval}",
            initialDelayString = "${job.matching-seat-reservation.expiry-sweep-interval}"
    )
    public void expireOverdueReservations() {
        try {
            expiryService.expireOverdueReservations();
        } catch (RuntimeException exception) {
            log.warn("모집 자리 예약 만료 회수 실행 실패: type={}", exception.getClass().getName());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(
            name = "job.matching-seat-reservation.expiry-sweep-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    static class Scheduling {
    }
}
