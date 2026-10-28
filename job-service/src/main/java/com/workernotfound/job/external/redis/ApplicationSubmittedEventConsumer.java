package com.workernotfound.job.external.redis;

import com.workernotfound.job.domain.job.entity.enums.AdmissionConsumptionResult;
import com.workernotfound.job.domain.job.event.ApplicationSubmittedEvent;
import com.workernotfound.job.domain.job.exception.InvalidAdmissionEventException;
import com.workernotfound.job.domain.job.service.AdmissionEventOutcome;
import com.workernotfound.job.domain.job.service.JobAdmissionEventReceiptService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code matching:domain-events}의 {@code ApplicationSubmitted}를 job-service 전용 그룹으로 소비해 승인 사용 완료를 처리한다.
 *
 * <p>ACK 규칙:
 * <ul>
 *     <li>대상이 아닌 이벤트 종류·aggregateType은 바로 ACK한다.</li>
 *     <li>처리 결과(사용 완료, 중복, 만료 뒤 지원, 영구 거절)는 수신 기록과 함께 커밋된 뒤 ACK한다. 영구 거절은 오류 로그를 남긴다.</li>
 *     <li>해석할 수 없는 envelope, 같은 eventId의 다른 내용, 지원하지 않는 스키마 버전은 기록하지 않고 ACK하지 않는다.
 *     work·chat·notification 소비자처럼 pending으로 남겨 오류 로그로 알리고 운영자가 확인하게 한다.</li>
 *     <li>DB 장애 같은 일시 오류와 ACK 실패도 pending으로 남는다. 다음 poll의 pending 재수신에서 다시 처리하며, 이미 커밋된
 *     이벤트는 수신 기록으로 중복 판단되어 승인을 다시 바꾸지 않는다.</li>
 * </ul>
 *
 * <p>pending은 매 poll마다 회전 커서로 batch-size만큼 다시 읽은 뒤 새 레코드를 읽는다. 처리되지 않는 레코드가 앞에 쌓여도
 * 뒤 레코드의 복구와 새 레코드 처리를 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "job.admission-events.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AdmissionEventConsumerProperties.class)
public class ApplicationSubmittedEventConsumer {

    static final String GROUP = "job-admission-consumption-v1";
    // 여러 인스턴스가 하나의 논리 소비자를 공유한다. 어느 인스턴스든 pending을 복구할 수 있고, 동시 재전달은 MySQL이 직렬화한다.
    static final String CONSUMER = "job-admission-consumption";
    private static final String FIRST_ID = "0-0";

    private final StringRedisTemplate redisTemplate;
    private final ApplicationSubmittedEventParser parser;
    private final JobAdmissionEventReceiptService receiptService;
    private final AdmissionEventConsumerProperties properties;
    private String pendingCursor = FIRST_ID;

    @Scheduled(
            fixedDelayString = "${job.admission-events.poll-interval}",
            initialDelayString = "${job.admission-events.initial-delay}"
    )
    public synchronized void poll() {
        try {
            ensureGroup();
            List<MapRecord<String, Object, Object>> pending = read(ReadOffset.from(pendingCursor));
            pendingCursor = pending.isEmpty() ? FIRST_ID : pending.get(pending.size() - 1).getId().getValue();
            pending.forEach(this::handle);
            read(ReadOffset.lastConsumed()).forEach(this::handle);
        } catch (RuntimeException exception) {
            log.warn("ApplicationSubmitted 이벤트 poll 실패: type={}", exception.getClass().getName());
        }
    }

    // 그룹이 없으면 Stream 처음부터 읽도록 만든다. 소비자 연결 전에 발행된 지원 이벤트도 처리한다.
    private void ensureGroup() {
        try {
            redisTemplate.execute((RedisCallback<Object>) connection -> connection.execute(
                    "XGROUP", bytes("CREATE"), bytes(properties.stream()), bytes(GROUP), bytes(FIRST_ID),
                    bytes("MKSTREAM")));
        } catch (RuntimeException exception) {
            if (!isBusyGroup(exception)) {
                throw exception;
            }
        }
    }

    private boolean isBusyGroup(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().startsWith("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(properties.batchSize()),
                StreamOffset.create(properties.stream(), offset));
        return records == null ? List.of() : records;
    }

    private void handle(MapRecord<String, Object, Object> record) {
        try {
            if (parser.isTarget(record.getValue())) {
                ApplicationSubmittedEvent event = parser.parse(record.getValue());
                logOutcome(record, event, receiptService.receive(event));
            }
            // 처리 트랜잭션이 커밋된 뒤에만 ACK한다. 다른 서비스 그룹의 수신에 영향을 주지 않도록 Stream은 trim하지 않는다.
            redisTemplate.opsForStream().acknowledge(properties.stream(), GROUP, record.getId());
        } catch (InvalidAdmissionEventException exception) {
            log.error("해석할 수 없는 ApplicationSubmitted를 pending으로 남깁니다: recordId={}, reason={}",
                    record.getId(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("ApplicationSubmitted 처리를 재시도하도록 pending으로 남깁니다: recordId={}, type={}",
                    record.getId(), exception.getClass().getName());
        }
    }

    private void logOutcome(
            MapRecord<String, Object, Object> record,
            ApplicationSubmittedEvent event,
            AdmissionEventOutcome outcome
    ) {
        if (outcome.result().isRejected()) {
            log.error("ApplicationSubmitted 영구 거절을 기록하고 ACK합니다: recordId={}, eventId={}, admissionId={}, "
                            + "result={}, duplicate={}",
                    record.getId(), event.eventId(), event.admissionId(), outcome.result(), outcome.isDuplicate());
        } else if (outcome.result() == AdmissionConsumptionResult.SUBMITTED_AFTER_EXPIRY) {
            log.warn("만료 시각 이후의 ApplicationSubmitted를 기록하고 ACK합니다: recordId={}, eventId={}, admissionId={}, "
                            + "duplicate={}",
                    record.getId(), event.eventId(), event.admissionId(), outcome.isDuplicate());
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "job.admission-events.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
    }
}
