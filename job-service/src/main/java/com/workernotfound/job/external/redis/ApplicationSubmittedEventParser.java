package com.workernotfound.job.external.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.job.domain.job.event.ApplicationSubmittedEvent;
import com.workernotfound.job.domain.job.exception.InvalidAdmissionEventException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * matching-service가 {@code matching:domain-events}에 발행한 레코드를 {@link ApplicationSubmittedEvent}로 해석한다.
 *
 * <p>envelope 필드는 matching-service {@code RedisStreamOutboxEventPublisher}가 문자열로 넣는다. {@code occurredAt}은
 * Outbox의 {@code DATETIME(6)} 저장값을 {@code LocalDateTime.toString()}으로 쓴 값이고, payload의 발생 시각은 저장 전 나노초 값이다.
 * 대상 판별은 envelope의 이벤트 종류와 aggregateType만으로 한다. 대상 이벤트의 envelope와 payload가 맞지 않거나 해석할 수 없으면
 * {@link InvalidAdmissionEventException}을 던진다. admissionId 같은 업무 값이 비어 있는 것은 여기서 거절하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ApplicationSubmittedEventParser {

    static final String EVENT_TYPE = "ApplicationSubmitted";
    static final String AGGREGATE_TYPE = "APPLICATION";
    private static final int SUPPORTED_VERSION = 1;
    // 수신 기록 컬럼(VARCHAR(128) ascii)에 저장할 수 있는 값만 받는다.
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("[!-~]{1,128}");

    private final ObjectMapper objectMapper;

    public boolean isTarget(Map<Object, Object> fields) {
        return EVENT_TYPE.equals(fields.get("eventType")) && AGGREGATE_TYPE.equals(fields.get("aggregateType"));
    }

    public ApplicationSubmittedEvent parse(Map<Object, Object> fields) {
        String eventId = requireEventId(fields.get("eventId"));
        long aggregateId = requirePositiveLong(fields.get("aggregateId"), "aggregateId");
        long revision = requirePositiveLong(fields.get("revision"), "revision");
        requireSupportedVersion(fields.get("version"));
        LocalDateTime occurredAt = parseOccurredAt(fields.get("occurredAt"));
        ApplicationSubmittedPayload payload = readPayload(fields.get("payload"));
        if (!hasMatchingEnvelope(payload, eventId, aggregateId, revision)
                || !isSameOccurrence(occurredAt, payload.occurredAt())) {
            throw new InvalidAdmissionEventException("ApplicationSubmitted envelope와 payload가 일치하지 않습니다.");
        }
        return new ApplicationSubmittedEvent(eventId, aggregateId, revision, payload.admissionId(),
                payload.jobPostId(), payload.workerMemberId(), occurredAt);
    }

    private String requireEventId(Object value) {
        if (value instanceof String eventId && EVENT_ID_PATTERN.matcher(eventId).matches()) {
            return eventId;
        }
        throw new InvalidAdmissionEventException("ApplicationSubmitted eventId가 없거나 형식이 잘못되었습니다.");
    }

    private long requirePositiveLong(Object value, String name) {
        String message = "ApplicationSubmitted %s가 양의 정수가 아닙니다.".formatted(name);
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            if (parsed <= 0) {
                throw new InvalidAdmissionEventException(message);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new InvalidAdmissionEventException(message, exception);
        }
    }

    // 새 스키마 버전은 해석 규칙이 정해질 때까지 pending으로 남긴다.
    private void requireSupportedVersion(Object value) {
        if (!String.valueOf(SUPPORTED_VERSION).equals(value)) {
            throw new InvalidAdmissionEventException("지원하지 않는 ApplicationSubmitted 스키마 버전입니다.");
        }
    }

    // 발생 시각이 아예 없으면 거절 결과로 기록할 수 있도록 null을 돌려준다. 값이 있는데 해석할 수 없으면 계약 위반이다.
    private LocalDateTime parseOccurredAt(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (DateTimeParseException exception) {
            throw new InvalidAdmissionEventException("ApplicationSubmitted occurredAt 형식이 잘못되었습니다.", exception);
        }
    }

    // Jackson 오류 메시지에는 payload 일부가 담길 수 있어 원인 예외로만 보존한다.
    private ApplicationSubmittedPayload readPayload(Object value) {
        if (!(value instanceof String json)) {
            throw new InvalidAdmissionEventException("ApplicationSubmitted payload가 없습니다.");
        }
        try {
            ApplicationSubmittedPayload payload = objectMapper.readValue(json, ApplicationSubmittedPayload.class);
            if (payload == null) {
                throw new InvalidAdmissionEventException("ApplicationSubmitted payload가 비어 있습니다.");
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new InvalidAdmissionEventException("ApplicationSubmitted payload를 해석할 수 없습니다.", exception);
        }
    }

    private boolean hasMatchingEnvelope(
            ApplicationSubmittedPayload payload,
            String eventId,
            long aggregateId,
            long revision
    ) {
        return eventId.equals(payload.eventId())
                && EVENT_TYPE.equals(payload.eventType())
                && Objects.equals(aggregateId, payload.aggregateId())
                && Objects.equals(aggregateId, payload.applicationId())
                && Objects.equals(revision, payload.revision())
                && Objects.equals(SUPPORTED_VERSION, payload.version());
    }

    // Outbox의 DATETIME(6) 저장은 payload의 마이크로초 미만을 절삭하거나 반올림할 수 있다. 그 차이만 같은 발생 시각으로 본다.
    private boolean isSameOccurrence(LocalDateTime envelopeTime, LocalDateTime payloadTime) {
        if (envelopeTime == null || payloadTime == null) {
            return envelopeTime == null && payloadTime == null;
        }
        if (envelopeTime.equals(payloadTime)) {
            return true;
        }
        LocalDateTime truncated = payloadTime.truncatedTo(ChronoUnit.MICROS);
        boolean hasSubMicros = !payloadTime.equals(truncated);
        return hasSubMicros && (envelopeTime.equals(truncated) || envelopeTime.equals(truncated.plusNanos(1_000)));
    }
}
