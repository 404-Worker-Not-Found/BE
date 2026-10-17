package com.workernotfound.payment.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 발행 서비스가 Redis Stream에 넣는 레코드를 그대로 만든다.
 *
 * <p>payload 레코드는 matching-service {@code MatchingEvent}, work-service {@code WorkLifecycleEvent}의 필드 이름·순서·타입과
 * 같다. 각 서비스 {@code OpenApiConfig}의 ObjectMapper 설정으로 직렬화한다. matching-service는 날짜를 숫자 배열로, work-service는
 * ISO 문자열로 쓴다. envelope는 각 서비스의 Outbox relay처럼 모든 값을 문자열로 넣고, occurredAt은 Outbox {@code DATETIME(6)}에
 * 저장된 값을 {@code LocalDateTime.toString()}으로 쓴다. 발행 계약이 바뀌면 이 픽스처도 함께 바꾼다.
 */
public final class SettlementEventFixture {
  public static final LocalDate WORK_DATE = LocalDate.of(2026, 10, 18);
  public static final LocalTime START_TIME = LocalTime.of(9, 0);
  public static final LocalTime END_TIME = LocalTime.of(18, 0);

  private static final ObjectMapper MATCHING_OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final ObjectMapper WORK_OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private SettlementEventFixture() {}

  record MatchingEventPayload(String eventId, String eventType, LocalDateTime occurredAt, Long aggregateId,
      String correlationId, Long revision, Integer version, Long matchingId, Long applicationId, Long jobPostId,
      Long ownerMemberId, Long workerMemberId, String workId, String paymentId, String chatRoomId,
      LocalDate workDate, LocalTime startTime, LocalTime endTime, BigDecimal lockedAmount, String currency) {}

  record WorkLifecyclePayload(String eventId, String eventType, Long aggregateId, long revision, int version,
      LocalDateTime occurredAt, Long workId, Long matchingId, Long jobPostId, Long ownerMemberId,
      Long workerMemberId, Long actorMemberId, String actorRole, String status, LocalDate workDate,
      LocalTime startTime, LocalTime endTime, boolean endTimeNextDay) {}

  public record Confirmation(String eventId, long matchingId, long revision, String workId, String paymentId,
      long jobPostId, long ownerMemberId, long workerMemberId, BigDecimal lockedAmount, String currency,
      LocalDateTime occurredAt) {}

  public record WorkEvent(String eventId, String eventType, long revision, String status, long workId,
      Long matchingId, Long jobPostId, Long ownerMemberId, Long workerMemberId, LocalDateTime occurredAt) {}

  public static Map<String, String> matchConfirmed(Confirmation event) {
    var payload = new MatchingEventPayload(event.eventId(), "MatchConfirmed", event.occurredAt(),
        event.matchingId(), String.valueOf(event.matchingId()), event.revision(), 1, event.matchingId(),
        event.matchingId() + 7, event.jobPostId(), event.ownerMemberId(), event.workerMemberId(),
        event.workId(), event.paymentId(), "88", WORK_DATE, START_TIME, END_TIME, event.lockedAmount(),
        event.currency());
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("eventId", event.eventId());
    fields.put("aggregateType", "MATCHING");
    fields.put("aggregateId", String.valueOf(event.matchingId()));
    fields.put("eventType", "MatchConfirmed");
    fields.put("correlationId", String.valueOf(event.matchingId()));
    fields.put("revision", String.valueOf(event.revision()));
    fields.put("version", "1");
    fields.put("occurredAt", roundToStoredMicros(event.occurredAt()).toString());
    fields.put("payload", json(MATCHING_OBJECT_MAPPER, payload));
    return fields;
  }

  // work-service는 발생 시각을 마이크로초로 자른 뒤 저장·발행하므로 envelope와 payload 시각이 같다.
  public static Map<String, String> workEvent(WorkEvent event) {
    String actorRole = "WorkCheckedIn".equals(event.eventType()) ? "WORKER" : "OWNER";
    Long actor = "WORKER".equals(actorRole) ? event.workerMemberId() : event.ownerMemberId();
    var payload = new WorkLifecyclePayload(event.eventId(), event.eventType(), event.workId(), event.revision(),
        1, event.occurredAt(), event.workId(), event.matchingId(), event.jobPostId(), event.ownerMemberId(),
        event.workerMemberId(), actor, actorRole, event.status(), WORK_DATE, START_TIME, END_TIME, false);
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("eventId", event.eventId());
    fields.put("aggregateType", "WORK");
    fields.put("aggregateId", String.valueOf(event.workId()));
    fields.put("eventType", event.eventType());
    fields.put("revision", String.valueOf(event.revision()));
    fields.put("version", "1");
    fields.put("occurredAt", event.occurredAt().toString());
    fields.put("payload", json(WORK_OBJECT_MAPPER, payload));
    return fields;
  }

  // MySQL DATETIME(6)은 마이크로초 미만을 반올림해 저장한다.
  public static LocalDateTime roundToStoredMicros(LocalDateTime value) {
    return value.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
  }

  private static String json(ObjectMapper mapper, Object payload) {
    try {
      return mapper.writeValueAsString(payload);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
