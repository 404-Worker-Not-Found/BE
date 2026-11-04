package com.workernotfound.job.external.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.job.domain.job.entity.enums.ApplicationStatus;
import com.workernotfound.job.domain.job.event.ApplicationStatusChangedEvent;
import com.workernotfound.job.domain.job.exception.InvalidApplicationStateException;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * matching-service가 {@code matching:domain-events}에 발행한 지원 상태 이벤트를 {@link ApplicationStatusChangedEvent}로 해석한다.
 *
 * <p>대상은 aggregateType {@code APPLICATION}의 {@code ApplicationSubmitted}·{@code ApplicationCanceled}·
 * {@code ApplicationSelected}·{@code ApplicationRejected}다. envelope와 payload의 eventId·이벤트 종류·지원 ID·revision·스키마
 * 버전이 같아야 하고, payload 상태는 이벤트 종류가 뜻하는 상태여야 한다. 어긋나거나 해석할 수 없으면
 * {@link InvalidApplicationStateException}을 던진다.
 */
@Component
@RequiredArgsConstructor
public class ApplicationStatusEventParser {

    static final String AGGREGATE_TYPE = "APPLICATION";
    private static final Map<String, ApplicationStatus> STATUS_BY_EVENT_TYPE = Map.of(
            "ApplicationSubmitted", ApplicationStatus.APPLIED,
            "ApplicationCanceled", ApplicationStatus.CANCELED,
            "ApplicationSelected", ApplicationStatus.SELECTED,
            "ApplicationRejected", ApplicationStatus.REJECTED
    );
    private static final int SUPPORTED_VERSION = 1;
    // projection 컬럼(VARCHAR(128) ascii)에 저장할 수 있는 값만 받는다.
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("[!-~]{1,128}");

    private final ObjectMapper objectMapper;

    public boolean isTarget(Map<Object, Object> fields) {
        return AGGREGATE_TYPE.equals(fields.get("aggregateType"))
                && fields.get("eventType") instanceof String eventType
                && STATUS_BY_EVENT_TYPE.containsKey(eventType);
    }

    public ApplicationStatusChangedEvent parse(Map<Object, Object> fields) {
        String eventType = String.valueOf(fields.get("eventType"));
        String eventId = requireEventId(fields.get("eventId"));
        long aggregateId = requirePositiveLong(fields.get("aggregateId"), "aggregateId");
        long revision = requirePositiveLong(fields.get("revision"), "revision");
        requireSupportedVersion(fields.get("version"));
        ApplicationStatusPayload payload = readPayload(fields.get("payload"));
        if (!hasMatchingEnvelope(payload, eventId, eventType, aggregateId, revision)) {
            throw new InvalidApplicationStateException("지원 상태 이벤트 envelope와 payload가 일치하지 않습니다.");
        }
        if (payload.jobPostId() == null || payload.jobPostId() <= 0) {
            throw new InvalidApplicationStateException("지원 상태 이벤트 jobPostId가 양의 정수가 아닙니다.");
        }
        ApplicationStatus status = STATUS_BY_EVENT_TYPE.get(eventType);
        if (!status.name().equals(payload.status())) {
            throw new InvalidApplicationStateException("지원 상태 이벤트의 상태가 이벤트 종류와 다릅니다.");
        }
        return new ApplicationStatusChangedEvent(eventId, aggregateId, payload.jobPostId(), status, revision);
    }

    private String requireEventId(Object value) {
        if (value instanceof String eventId && EVENT_ID_PATTERN.matcher(eventId).matches()) {
            return eventId;
        }
        throw new InvalidApplicationStateException("지원 상태 이벤트 eventId가 없거나 형식이 잘못되었습니다.");
    }

    private long requirePositiveLong(Object value, String name) {
        String message = "지원 상태 이벤트 %s가 양의 정수가 아닙니다.".formatted(name);
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            if (parsed <= 0) {
                throw new InvalidApplicationStateException(message);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new InvalidApplicationStateException(message, exception);
        }
    }

    // 새 스키마 버전은 해석 규칙이 정해질 때까지 pending으로 남긴다.
    private void requireSupportedVersion(Object value) {
        if (!String.valueOf(SUPPORTED_VERSION).equals(value)) {
            throw new InvalidApplicationStateException("지원하지 않는 지원 상태 이벤트 스키마 버전입니다.");
        }
    }

    // Jackson 오류 메시지에는 payload 일부가 담길 수 있어 원인 예외로만 보존한다.
    private ApplicationStatusPayload readPayload(Object value) {
        if (!(value instanceof String json)) {
            throw new InvalidApplicationStateException("지원 상태 이벤트 payload가 없습니다.");
        }
        try {
            ApplicationStatusPayload payload = objectMapper.readValue(json, ApplicationStatusPayload.class);
            if (payload == null) {
                throw new InvalidApplicationStateException("지원 상태 이벤트 payload가 비어 있습니다.");
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new InvalidApplicationStateException("지원 상태 이벤트 payload를 해석할 수 없습니다.", exception);
        }
    }

    private boolean hasMatchingEnvelope(
            ApplicationStatusPayload payload,
            String eventId,
            String eventType,
            long aggregateId,
            long revision
    ) {
        return eventId.equals(payload.eventId())
                && eventType.equals(payload.eventType())
                && Objects.equals(aggregateId, payload.aggregateId())
                && Objects.equals(aggregateId, payload.applicationId())
                && Objects.equals(revision, payload.revision())
                && Objects.equals(SUPPORTED_VERSION, payload.version());
    }
}
