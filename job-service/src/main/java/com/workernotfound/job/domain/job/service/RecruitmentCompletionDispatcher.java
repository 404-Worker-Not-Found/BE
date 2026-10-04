package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.RecruitmentCompletionCommand;
import com.workernotfound.job.domain.job.exception.RecruitmentCompletionNotificationException;
import com.workernotfound.job.domain.job.port.RecruitmentCompletionNotifier;
import com.workernotfound.job.domain.job.repository.RecruitmentCompletionCommandRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 저장된 모집 완료 알림 명령을 matching-service에 전송한다.
 *
 * <p>한 번의 시도는 (1) 짧은 트랜잭션에서 실행권 획득·커밋, (2) 트랜잭션과 잠금 없이 HTTP 호출, (3) 별도의 짧은 트랜잭션에서
 * 실행권 토큰을 조건으로 결과 기록 순서로 진행한다. 실행 중 프로세스가 멈추면 실행권 만료 후 다른 실행자가 같은 명령 ID와
 * 완료 버전으로 다시 보낸다. 상대 서비스는 같은 버전의 완료를 멱등하게 처리하므로 응답 유실이나 로컬 기록 실패도 재전송으로 수렴한다.
 */
@Slf4j
@Service
@EnableConfigurationProperties(RecruitmentCompletionDispatchProperties.class)
public class RecruitmentCompletionDispatcher {

    private static final int MAX_BACKOFF_EXPONENT = 20;

    private final RecruitmentCompletionCommandRepository commandRepository;
    private final RecruitmentCompletionCommandTransactionService transactionService;
    private final RecruitmentCompletionNotifier notifier;
    private final RecruitmentCompletionDispatchProperties properties;
    private final Clock clock;

    public RecruitmentCompletionDispatcher(
            RecruitmentCompletionCommandRepository commandRepository,
            RecruitmentCompletionCommandTransactionService transactionService,
            RecruitmentCompletionNotifier notifier,
            RecruitmentCompletionDispatchProperties properties,
            Clock clock
    ) {
        validateLeaseCoversHttpCall(properties, notifier.maxCallDuration());
        this.commandRepository = commandRepository;
        this.transactionService = transactionService;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    // 다음 시도 시각이 지났고 유효한 실행권이 없는 명령을 배치 크기만큼 전송한다. 한 명령의 실패가 다른 명령을 막지 않는다.
    public int dispatchDue() {
        List<Long> ids = commandRepository.findDueIds(storedNow(), PageRequest.of(0, properties.batchSize()));
        int dispatched = 0;
        for (Long id : ids) {
            if (dispatchSafely(id)) {
                dispatched++;
            }
        }
        return dispatched;
    }

    /**
     * 실행권을 얻은 경우에만 전송한다. 다른 실행자가 이미 실행 중이거나 다음 시도 시각 전이면 아무것도 하지 않는다.
     *
     * @return 실행권을 얻어 전송을 시도했으면 true
     */
    public boolean dispatch(Long id) {
        LocalDateTime now = storedNow();
        String leaseToken = UUID.randomUUID().toString();
        return transactionService.claim(id, leaseToken, now, now.plus(properties.leaseDuration()))
                .map(dispatch -> {
                    send(dispatch);
                    return true;
                })
                .orElse(false);
    }

    private boolean dispatchSafely(Long id) {
        try {
            return dispatch(id);
        } catch (RuntimeException exception) {
            // 결과 기록이 실패해도 실행권이 만료되면 다시 전송된다.
            log.warn("모집 완료 알림 전송 처리 실패: id={}, type={}", id, exception.getClass().getName());
            return false;
        }
    }

    private void send(RecruitmentCompletionDispatch dispatch) {
        try {
            notifier.notifyRecruitmentCompleted(dispatch.jobPostId(), dispatch.jobVersion(), dispatch.commandId());
        } catch (RecruitmentCompletionNotificationException failure) {
            recordFailure(dispatch, failure);
            return;
        }
        if (!transactionService.markSucceeded(dispatch.id(), dispatch.leaseToken(), storedNow())) {
            log.info("모집 완료 알림 성공 결과를 기록하지 않음(실행권 만료 후 인계됨): commandId={}", dispatch.commandId());
            return;
        }
        log.info("모집 완료 알림 전송 성공: commandId={}, jobPostId={}, jobVersion={}, attempt={}",
                dispatch.commandId(), dispatch.jobPostId(), dispatch.jobVersion(), dispatch.attemptCount());
    }

    private void recordFailure(RecruitmentCompletionDispatch dispatch, RecruitmentCompletionNotificationException failure) {
        LocalDateTime now = storedNow();
        LocalDateTime nextAttemptAt = RecruitmentCompletionCommand.toStoredTime(now.plus(retryDelay(dispatch, failure)));
        if (!transactionService.markFailed(dispatch.id(), dispatch.leaseToken(), failure, nextAttemptAt, now)) {
            log.info("모집 완료 알림 실패 결과를 기록하지 않음(실행권 만료 후 인계됨): commandId={}", dispatch.commandId());
            return;
        }
        logFailure(dispatch, failure, nextAttemptAt);
    }

    // 일시 오류는 시도마다 두 배로 늘리되 최대 지연을 넘지 않는다. 인증·계약 오류는 원인 해결 전까지 최대 지연으로만 다시 확인한다.
    private Duration retryDelay(RecruitmentCompletionDispatch dispatch, RecruitmentCompletionNotificationException failure) {
        if (!failure.getFailureType().isTransient()) {
            return properties.retryMaxDelay();
        }
        int exponent = Math.min(Math.max(dispatch.attemptCount() - 1, 0), MAX_BACKOFF_EXPONENT);
        Duration delay = properties.retryBaseDelay().multipliedBy(1L << exponent);
        return delay.compareTo(properties.retryMaxDelay()) > 0 ? properties.retryMaxDelay() : delay;
    }

    private void logFailure(
            RecruitmentCompletionDispatch dispatch,
            RecruitmentCompletionNotificationException failure,
            LocalDateTime nextAttemptAt
    ) {
        String format = "모집 완료 알림 전송 실패: commandId={}, jobPostId={}, jobVersion={}, attempt={}, "
                + "failureType={}, status={}, code={}, nextAttemptAt={}";
        Object[] arguments = {
                dispatch.commandId(), dispatch.jobPostId(), dispatch.jobVersion(), dispatch.attemptCount(),
                failure.getFailureType(), failure.getHttpStatus(), failure.getResponseCode(), nextAttemptAt
        };
        if (failure.getFailureType().isTransient()) {
            log.warn(format, arguments);
            return;
        }
        // 인증·계약 오류는 재시도만으로 해결되지 않으므로 운영 확인이 필요하다.
        log.error("[운영 확인 필요] " + format, arguments);
    }

    private LocalDateTime storedNow() {
        return RecruitmentCompletionCommand.toStoredTime(LocalDateTime.now(clock));
    }

    // 실행권이 HTTP 응답 대기 중에 만료되면 다른 실행자가 같은 명령을 동시에 보낼 수 있다. 연결·응답 타임아웃 합보다 길게 둔다.
    private static void validateLeaseCoversHttpCall(
            RecruitmentCompletionDispatchProperties properties,
            Duration maxCallDuration
    ) {
        if (properties.leaseDuration().compareTo(maxCallDuration) <= 0) {
            throw new IllegalArgumentException(
                    "job.recruitment-completion.lease-duration은 matching-service 연결·응답 타임아웃 합보다 길어야 합니다.");
        }
    }
}
