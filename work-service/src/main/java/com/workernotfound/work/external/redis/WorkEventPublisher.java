package com.workernotfound.work.external.redis;

import com.workernotfound.work.domain.outbox.*;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkEventPublisher {
  private final StringRedisTemplate redis;
  private final WorkOutboxProperties properties;

  public void publish(WorkOutboxMessage message) {
    var fields = Map.of("eventId", message.eventId(), "aggregateType", "WORK",
        "aggregateId", Long.toString(message.workId()), "eventType", message.eventType(),
        "revision", Long.toString(message.revision()), "version", Integer.toString(message.version()),
        "occurredAt", message.occurredAt().toString(), "payload", message.payload());
    if (redis.opsForStream().add(StreamRecords.newRecord().in(properties.stream()).ofMap(fields)) == null)
      throw new IllegalStateException("근무 이벤트의 Redis Stream ID를 발급받지 못했습니다.");
  }
}
