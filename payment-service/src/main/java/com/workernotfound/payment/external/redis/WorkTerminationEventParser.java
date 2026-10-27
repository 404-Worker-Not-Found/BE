package com.workernotfound.payment.external.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.payment.domain.settlement.service.WorkTerminationEvent;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkTerminationEventParser {
  private static final Set<String> TYPES = Set.of("WorkCanceled", "WorkNoShow");
  private final ObjectMapper mapper;

  public boolean isTarget(Map<Object, Object> fields) {
    return TYPES.contains(String.valueOf(fields.get("eventType"))) && "WORK".equals(fields.get("aggregateType"));
  }

  public WorkTerminationEvent parse(Map<Object, Object> fields) {
    try {
      if (!(fields.get("payload") instanceof String payload)) throw new IllegalArgumentException("근무 이벤트 본문이 없습니다.");
      var event = mapper.readValue(payload, WorkTerminationEvent.class);
      if (event == null || !event.isValid() || !hasSameEnvelope(event, fields))
        throw new IllegalArgumentException("근무 종료 이벤트 계약이 일치하지 않습니다.");
      return event;
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalArgumentException("근무 종료 이벤트를 해석할 수 없습니다.");
    }
  }

  private boolean hasSameEnvelope(WorkTerminationEvent event, Map<Object, Object> fields) {
    return "WORK".equals(fields.get("aggregateType")) && Objects.equals(event.eventId(), fields.get("eventId"))
        && Objects.equals(event.eventType(), fields.get("eventType"))
        && Objects.equals(String.valueOf(event.aggregateId()), fields.get("aggregateId"))
        && Objects.equals(String.valueOf(event.revision()), fields.get("revision"))
        && Objects.equals(String.valueOf(event.version()), fields.get("version")) && sameTime(event.occurredAt(), fields.get("occurredAt"));
  }

  private boolean sameTime(LocalDateTime payload, Object value) {
    if (!(value instanceof String text)) return false;
    try {
      var envelope = LocalDateTime.parse(text);
      if (envelope.equals(payload)) return true;
      var truncated = payload.truncatedTo(ChronoUnit.MICROS);
      return !truncated.equals(payload) && (envelope.equals(truncated) || envelope.equals(truncated.plusNanos(1000)));
    } catch (java.time.format.DateTimeParseException exception) { return false; }
  }
}
