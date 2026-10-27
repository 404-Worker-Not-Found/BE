package com.workernotfound.matching.external.redis;

import com.workernotfound.matching.domain.matching.service.WorkTerminationService;
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
@ConditionalOnProperty(name = "matching.work-events.enabled", havingValue = "true", matchIfMissing = true)
public class WorkTerminationEventConsumer {
  private static final String GROUP = "matching-work-termination-v1";
  private static final String CONSUMER = "matching-work-termination";
  private final StringRedisTemplate redis;
  private final WorkTerminationEventParser parser;
  private final WorkTerminationService service;
  private final String stream;
  private String pendingCursor = "0-0";

  public WorkTerminationEventConsumer(StringRedisTemplate redis, WorkTerminationEventParser parser,
      WorkTerminationService service, @Value("${matching.work-events.stream:work:domain-events}") String stream) {
    this.redis = redis;
    this.parser = parser;
    this.service = service;
    this.stream = stream;
  }

  @Scheduled(fixedDelayString = "${matching.work-events.poll-delay-ms:1000}",
      initialDelayString = "${matching.work-events.initial-delay-ms:0}")
  public synchronized void poll() {
    try {
      ensureGroup();
      var pending = read(ReadOffset.from(pendingCursor));
      pendingCursor = pending.isEmpty() ? "0-0" : pending.get(pending.size() - 1).getId().getValue();
      process(pending);
      process(read(ReadOffset.lastConsumed()));
    } catch (RuntimeException exception) {
      log.warn("근무 종료 이벤트 poll 재시도: type={}", exception.getClass().getSimpleName());
    }
  }

  private void ensureGroup() {
    try {
      redis.execute((RedisCallback<Object>) connection -> connection.execute("XGROUP", bytes("CREATE"),
          bytes(stream), bytes(GROUP), bytes("0-0"), bytes("MKSTREAM")));
    } catch (RuntimeException exception) {
      for (Throwable cause = exception; cause != null; cause = cause.getCause())
        if (cause.getMessage() != null && cause.getMessage().startsWith("BUSYGROUP")) return;
      throw exception;
    }
  }

  private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
    var records = redis.opsForStream().read(Consumer.from(GROUP, CONSUMER), StreamReadOptions.empty().count(100), StreamOffset.create(stream, offset));
    return records == null ? List.of() : records;
  }

  private void process(List<MapRecord<String, Object, Object>> records) {
    for (var record : records) {
      try {
        if (parser.isTarget(record.getValue())) {
          String result = service.receive(parser.parse(record.getValue()));
          if (result.startsWith("REJECTED")) log.warn("근무 종료 거절 기록: recordId={}, result={}", record.getId(), result);
        }
        redis.opsForStream().acknowledge(stream, GROUP, record.getId());
      } catch (RuntimeException exception) {
        log.warn("근무 종료 이벤트 pending 유지: recordId={}, type={}", record.getId(), exception.getClass().getSimpleName());
      }
    }
  }

  private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
