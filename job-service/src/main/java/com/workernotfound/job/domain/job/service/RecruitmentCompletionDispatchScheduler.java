package com.workernotfound.job.domain.job.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 커밋 후 즉시 전송 여부와 관계없이 DB의 미완료 모집 완료 알림 명령을 주기적으로 찾아 전송한다.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "job.recruitment-completion.dispatch-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RecruitmentCompletionDispatchScheduler {

    private final RecruitmentCompletionDispatcher dispatcher;

    @Scheduled(
            fixedDelayString = "${job.recruitment-completion.dispatch-interval}",
            initialDelayString = "${job.recruitment-completion.dispatch-interval}"
    )
    public void dispatchDueCommands() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException exception) {
            log.warn("모집 완료 알림 전송 실행 실패: type={}", exception.getClass().getName());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(
            name = "job.recruitment-completion.dispatch-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    static class Scheduling {
    }
}
