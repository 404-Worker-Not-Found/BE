package com.workernotfound.job.domain.job.service;

import com.workernotfound.job.domain.job.entity.JobPaymentOrderCommand;
import com.workernotfound.job.domain.job.exception.PaymentOrderCreationException;
import com.workernotfound.job.domain.job.port.PaymentOrderCreator;
import com.workernotfound.job.domain.job.port.PaymentOrderCreator.CreatedPaymentOrder;
import com.workernotfound.job.domain.job.repository.JobPaymentOrderCommandRepository;
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
 * 저장된 결제 주문 생성 명령을 payment-service에 전송하고 검증된 주문을 공고에 연결한다.
 *
 * <p>한 번의 시도는 (1) 짧은 트랜잭션에서 실행권 획득·커밋, (2) 트랜잭션과 잠금 없이 HTTP 호출, (3) 별도의 짧은 트랜잭션에서
 * 실행권 토큰을 조건으로 결과 기록 순서로 진행한다. 응답 유실·타임아웃·프로세스 중단 뒤에는 같은 멱등 키와 같은 스냅샷으로 다시 보내며,
 * payment-service는 같은 키에 처음 만든 주문을 돌려주므로 원래 주문 ID가 복구된다.
 */
@Slf4j
@Service
@EnableConfigurationProperties(PaymentOrderDispatchProperties.class)
public class PaymentOrderDispatcher {

    private static final int MAX_BACKOFF_EXPONENT = 20;
    // 제한시간 초과 후 교환 취소와 결과 기록 트랜잭션에 쓰는 최소 여유
    static final Duration LEASE_MARGIN = Duration.ofSeconds(1);

    private final JobPaymentOrderCommandRepository commandRepository;
    private final PaymentOrderCommandTransactionService transactionService;
    private final PaymentOrderCreator creator;
    private final PaymentOrderDispatchProperties properties;
    private final Clock clock;

    public PaymentOrderDispatcher(
            JobPaymentOrderCommandRepository commandRepository,
            PaymentOrderCommandTransactionService transactionService,
            PaymentOrderCreator creator,
            PaymentOrderDispatchProperties properties,
            Clock clock
    ) {
        validateLeaseCoversHttpCall(properties, creator.maxCallDuration());
        this.commandRepository = commandRepository;
        this.transactionService = transactionService;
        this.creator = creator;
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
            // 분류된 전송 실패가 아닌 예외(DB 기록 실패, 내부 오류)다. 실행권이 만료되면 같은 명령이 다시 전송된다.
            // 예외 메시지·cause·suppressed에는 요청 헤더 값(내부 secret 포함) 같은 검증되지 않은 문자열이 담길 수 있으므로
            // 원본 예외를 넘기지 않고 명령 ID와 예외 타입 사슬만 남긴다.
            log.error("결제 주문 생성 명령 처리 실패: id={}, types={}", id, ExceptionTypeChain.describe(exception));
            return false;
        }
    }

    private void send(PaymentOrderDispatch dispatch) {
        CreatedPaymentOrder order;
        try {
            order = creator.createOrder(dispatch.request());
        } catch (PaymentOrderCreationException failure) {
            recordFailure(dispatch, failure);
            return;
        }
        PaymentOrderLinkResult result =
                transactionService.recordCreated(dispatch.id(), dispatch.leaseToken(), order.orderId(), storedNow());
        logResult(dispatch, order, result);
    }

    private void logResult(PaymentOrderDispatch dispatch, CreatedPaymentOrder order, PaymentOrderLinkResult result) {
        switch (result) {
            case LINKED -> log.info("결제 주문 연결: commandId={}, jobPostId={}, jobVersion={}, orderId={}, "
                            + "orderStatus={}, attempt={}", dispatch.id(), dispatch.jobPostId(),
                    dispatch.request().jobVersion(), order.orderId(), order.orderStatus(), dispatch.attemptCount());
            case SUPERSEDED -> log.warn("더 늦게 발급된 명령이 있어 결제 주문을 공고에 연결하지 않음: commandId={}, "
                    + "jobPostId={}, orderId={}", dispatch.id(), dispatch.jobPostId(), order.orderId());
            case LEASE_LOST -> log.info("결제 주문 생성 결과를 기록하지 않음(실행권 만료 후 인계됨): commandId={}",
                    dispatch.id());
        }
    }

    private void recordFailure(PaymentOrderDispatch dispatch, PaymentOrderCreationException failure) {
        LocalDateTime now = storedNow();
        LocalDateTime nextAttemptAt = JobPaymentOrderCommand.toStoredTime(now.plus(retryDelay(dispatch, failure)));
        if (!transactionService.markFailed(dispatch.id(), dispatch.leaseToken(), failure, nextAttemptAt, now)) {
            log.info("결제 주문 생성 실패 결과를 기록하지 않음(실행권 만료 후 인계됨): commandId={}", dispatch.id());
            return;
        }
        logFailure(dispatch, failure, nextAttemptAt);
    }

    // 일시 오류는 시도마다 두 배로 늘리되 최대 지연을 넘지 않는다. 그 밖의 오류는 원인 확인 전까지 최대 지연으로만 다시 확인한다.
    private Duration retryDelay(PaymentOrderDispatch dispatch, PaymentOrderCreationException failure) {
        if (!failure.getFailureType().isTransient()) {
            return properties.retryMaxDelay();
        }
        int exponent = Math.min(Math.max(dispatch.attemptCount() - 1, 0), MAX_BACKOFF_EXPONENT);
        Duration delay = properties.retryBaseDelay().multipliedBy(1L << exponent);
        return delay.compareTo(properties.retryMaxDelay()) > 0 ? properties.retryMaxDelay() : delay;
    }

    private void logFailure(
            PaymentOrderDispatch dispatch,
            PaymentOrderCreationException failure,
            LocalDateTime nextAttemptAt
    ) {
        String format = "결제 주문 생성 실패: commandId={}, jobPostId={}, jobVersion={}, attempt={}, "
                + "failureType={}, status={}, code={}, nextAttemptAt={}";
        Object[] arguments = {
                dispatch.id(), dispatch.jobPostId(), dispatch.request().jobVersion(), dispatch.attemptCount(),
                failure.getFailureType(), failure.getHttpStatus(), failure.getResponseCode(), nextAttemptAt
        };
        if (failure.getFailureType().isTransient()) {
            log.warn(format, arguments);
            return;
        }
        // 인증·충돌·계약·스냅샷 불일치·대체된 주문은 재시도만으로 해결되지 않으므로 운영 확인이 필요하다.
        log.error("[운영 확인 필요] " + format, arguments);
    }

    private LocalDateTime storedNow() {
        return JobPaymentOrderCommand.toStoredTime(LocalDateTime.now(clock));
    }

    // 실행권은 HTTP 호출 전체 제한시간(초과 시 교환을 취소해 연결을 닫음)에 취소·결과 기록 여유를 더한 값 이상이어야 한다.
    // 그래야 호출이 진행 중인 동안 실행권이 만료되어 다른 실행자가 같은 명령을 동시에 보내는 일이 없다.
    private static void validateLeaseCoversHttpCall(PaymentOrderDispatchProperties properties, Duration maxCallDuration) {
        Duration required = maxCallDuration.plus(LEASE_MARGIN);
        if (properties.leaseDuration().compareTo(required) < 0) {
            throw new IllegalArgumentException(
                    "job.payment-order.lease-duration은 payment-service 전체 호출 제한시간(call-timeout)보다 "
                            + LEASE_MARGIN.toMillis() + "ms 이상 길어야 합니다.");
        }
    }
}
