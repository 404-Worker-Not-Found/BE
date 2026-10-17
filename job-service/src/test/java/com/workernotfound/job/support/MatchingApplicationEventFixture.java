package com.workernotfound.job.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * matching-service가 {@code matching:domain-events}에 발행하는 지원 이벤트 레코드를 그대로 만든다.
 *
 * <p>envelope 필드와 순서는 matching-service {@code RedisStreamOutboxEventPublisher#fields}, payload는
 * {@code ApplicationEvent} 레코드를 matching-service {@code OpenApiConfig}의 {@code new ObjectMapper().findAndRegisterModules()}로
 * 직렬화한 결과와 같다(날짜는 숫자 배열). envelope의 {@code occurredAt}은 Outbox {@code DATETIME(6)}에 저장된 값이라
 * MySQL 기본 동작대로 마이크로초 반올림한 값을 쓴다. 두 서비스의 계약이 바뀌면 이 픽스처도 함께 바꾼다.
 */
public final class MatchingApplicationEventFixture {

    // matching-service ApplicationEvent와 같은 필드·순서다.
    record MatchingApplicationEvent(
            String eventId,
            String eventType,
            LocalDateTime occurredAt,
            Long aggregateId,
            String correlationId,
            Long revision,
            Integer version,
            Long applicationId,
            Long admissionId,
            Long jobPostId,
            Long workerMemberId,
            String status,
            LocalDateTime statusChangedAt
    ) {
    }

    private static final ObjectMapper MATCHING_OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private MatchingApplicationEventFixture() {
    }

    public static Map<String, String> submitted(
            Long applicationId,
            Long admissionId,
            Long jobPostId,
            Long workerMemberId,
            LocalDateTime occurredAt
    ) {
        return record(UUID.randomUUID().toString(), "ApplicationSubmitted", applicationId, admissionId, jobPostId,
                workerMemberId, occurredAt, "APPLIED");
    }

    public static Map<String, String> record(
            String eventId,
            String eventType,
            Long applicationId,
            Long admissionId,
            Long jobPostId,
            Long workerMemberId,
            LocalDateTime occurredAt,
            String status
    ) {
        MatchingApplicationEvent event = new MatchingApplicationEvent(eventId, eventType, occurredAt, applicationId,
                UUID.randomUUID().toString(), 1L, 1, applicationId, admissionId, jobPostId, workerMemberId, status,
                occurredAt);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", eventId);
        fields.put("aggregateType", "APPLICATION");
        fields.put("aggregateId", applicationId.toString());
        fields.put("eventType", eventType);
        fields.put("correlationId", event.correlationId());
        fields.put("revision", event.revision().toString());
        fields.put("version", event.version().toString());
        fields.put("occurredAt", roundToStoredMicros(occurredAt).toString());
        fields.put("payload", toJson(event));
        return fields;
    }

    public static LocalDateTime roundToStoredMicros(LocalDateTime value) {
        long micros = (value.getNano() + 500L) / 1_000L;
        return value.truncatedTo(ChronoUnit.SECONDS).plus(micros, ChronoUnit.MICROS);
    }

    private static String toJson(MatchingApplicationEvent event) {
        try {
            return MATCHING_OBJECT_MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
