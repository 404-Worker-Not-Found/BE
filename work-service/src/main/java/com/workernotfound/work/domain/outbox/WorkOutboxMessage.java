package com.workernotfound.work.domain.outbox;

import java.time.LocalDateTime;

public record WorkOutboxMessage(long id, String eventId, long workId, String eventType,
    long revision, int version, LocalDateTime occurredAt, String payload, int retryCount,
    String aggregateType, long aggregateId) {
  public WorkOutboxMessage(long id, String eventId, long workId, String eventType, long revision,
      int version, LocalDateTime occurredAt, String payload, int retryCount) {
    this(id, eventId, workId, eventType, revision, version, occurredAt, payload, retryCount, "WORK", workId);
  }
}
