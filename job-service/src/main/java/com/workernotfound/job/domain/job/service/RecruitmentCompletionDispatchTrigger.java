package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.event.RecruitmentCompletionCommandCreatedEvent;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 모집 완료 명령이 커밋되면 별도 스레드에서 바로 한 번 전송을 시도한다.
 *
 * <p>자리 확정 요청 스레드가 HTTP 응답을 기다리지 않도록 비동기로 실행한다. 이 경로는 빠른 전송을 위한 것이며
 * 실행되지 않거나 실패해도 스케줄러가 DB의 미완료 명령을 다시 찾아 전송한다.
 */
@Slf4j
@Component
public class RecruitmentCompletionDispatchTrigger {

    private static final int QUEUE_CAPACITY = 100;

    private final RecruitmentCompletionDispatcher dispatcher;
    private final RecruitmentCompletionDispatchProperties properties;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            new CustomizableThreadFactory("recruitment-completion-dispatch-")
    );

    public RecruitmentCompletionDispatchTrigger(
            RecruitmentCompletionDispatcher dispatcher,
            RecruitmentCompletionDispatchProperties properties
    ) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatchAfterCommit(RecruitmentCompletionCommandCreatedEvent event) {
        if (!properties.dispatchAfterCommit()) {
            return;
        }
        try {
            executor.execute(() -> dispatch(event.commandId()));
        } catch (RejectedExecutionException exception) {
            log.warn("모집 완료 알림 즉시 전송을 건너뜀(스케줄러가 복구): id={}", event.commandId());
        }
    }

    private void dispatch(Long commandId) {
        try {
            dispatcher.dispatch(commandId);
        } catch (RuntimeException exception) {
            log.warn("모집 완료 알림 즉시 전송 실패(스케줄러가 복구): id={}, type={}",
                    commandId, exception.getClass().getName());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
