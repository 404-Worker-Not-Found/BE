package com.workernotfound.notification.external.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workernotfound.notification.domain.notification.event.WorkNotificationEvent;
import com.workernotfound.notification.domain.notification.service.WorkNotificationEventService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@Slf4j
@ConditionalOnProperty(name = "notification.work-events.enabled", havingValue = "true", matchIfMissing = true)
public class WorkNotificationEventConsumer {
  private static final String GROUP = "notification-work-v1";
  private static final Set<String> SUPPORTED_EVENTS = Set.of("WorkCheckedIn", "WorkStarted", "WorkCompleted");
  // One logical consumer across replicas: every replica can recover its pending deliveries.
  // Concurrent delivery is safe because MySQL serializes notification updates and event receipts.
  private static final String CONSUMER = "notification-work";
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final WorkNotificationEventService service;
  private final String stream;
  private String pendingCursor = "0-0";

  public WorkNotificationEventConsumer(
      StringRedisTemplate redis,
      ObjectMapper mapper,
      WorkNotificationEventService service,
      @Value("${notification.work-events.stream:work:domain-events}") String stream) {
    this.redis = redis;
    this.mapper = mapper;
    this.service = service;
    this.stream = stream;
  }

  @Scheduled(
      fixedDelayString = "${notification.work-events.poll-delay-ms:1000}",
      initialDelayString = "${notification.work-events.initial-delay-ms:0}")
  public synchronized void poll() {
    try {
      ensureGroup();
      var pending = read(ReadOffset.from(pendingCursor));
      pendingCursor =
          pending.isEmpty() ? "0-0" : pending.get(pending.size() - 1).getId().getValue();
      process(pending);
      process(read(ReadOffset.lastConsumed()));
    } catch (RuntimeException exception) {
      log.warn("Work notification event poll failed: type={}", exception.getClass().getSimpleName());
    }
  }

  private void ensureGroup() {
    try {
      redis.execute(
          (RedisCallback<Object>)
              connection ->
                  connection.execute(
                      "XGROUP",
                      bytes("CREATE"),
                      bytes(stream),
                      bytes(GROUP),
                      bytes("0-0"),
                      bytes("MKSTREAM")));
    } catch (RuntimeException exception) {
      for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
        if (cause.getMessage() != null && cause.getMessage().startsWith("BUSYGROUP")) return;
      }
      throw exception;
    }
  }

  private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
    var records =
        redis
            .opsForStream()
            .read(
                Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(100),
                StreamOffset.create(stream, offset));
    return records == null ? List.of() : records;
  }

  private void process(List<MapRecord<String, Object, Object>> records) {
    for (var record : records) {
      try {
        if (isSupported(record.getValue())) {
          service.receive(parse(record.getValue()));
        }
        // The service transaction has committed before acknowledgment. Never trim the stream.
        redis.opsForStream().acknowledge(stream, GROUP, record.getId());
      } catch (RuntimeException | JsonProcessingException exception) {
        log.warn(
            "Work notification event retained for retry: recordId={}, type={}",
            record.getId(),
            exception.getClass().getSimpleName());
      }
    }
  }

  private boolean isSupported(Map<Object, Object> fields) throws JsonProcessingException {
    if (fields.get("eventType") instanceof String type && SUPPORTED_EVENTS.contains(type)) return true;
    if (!(fields.get("payload") instanceof String payload)) return false;
    var event = mapper.readTree(payload);
    return event != null && SUPPORTED_EVENTS.contains(event.path("eventType").asText());
  }

  private WorkNotificationEvent parse(Map<Object, Object> fields) throws JsonProcessingException {
    var event = mapper.readValue((String) fields.get("payload"), WorkNotificationEvent.class);
    String aggregateType = "WORK";
    if (!aggregateType.equals(fields.get("aggregateType"))
        || !Objects.equals(fields.get("eventId"), event.eventId())
        || !Objects.equals(fields.get("eventType"), event.eventType())
        || !Objects.equals(fields.get("aggregateId"), String.valueOf(event.aggregateId()))
        || !Objects.equals(fields.get("revision"), String.valueOf(event.revision()))
        || !Objects.equals(fields.get("version"), String.valueOf(event.version()))
        || !Objects.equals(java.time.LocalDateTime.parse((String) fields.get("occurredAt")), event.occurredAt()))
      throw new IllegalArgumentException("이벤트 envelope와 payload가 일치하지 않습니다.");
    return event;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
