package com.workernotfound.work.domain.outbox;

import com.workernotfound.work.external.redis.WorkEventPublisher;
import java.time.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOutboxRelay {
  private final WorkOutboxRepository repository;
  private final WorkEventPublisher publisher;
  private final WorkOutboxProperties properties;
  private final Clock clock;

  public void relay() {
    repository.candidates(now(), properties.batchSize()).forEach(this::relayOne);
  }

  private void relayOne(long id) {
    String token = UUID.randomUUID().toString();
    LocalDateTime at = now();
    repository.claim(id, token, at, at.plus(properties.leaseDuration()))
        .ifPresent(message -> publish(message, token));
  }

  private void publish(WorkOutboxMessage message, String token) {
    try {
      publisher.publish(message);
      repository.published(message.id(), token, now());
    } catch (RuntimeException exception) {
      repository.failed(message.id(), token, now().plus(retryDelay(message.retryCount())));
      log.warn("근무 이벤트 발행 실패: eventId={}, exceptionType={}", message.eventId(),
          exception.getClass().getSimpleName());
    }
  }

  private Duration retryDelay(int retries) {
    Duration delay = properties.retryBaseDelay().multipliedBy(1L << Math.min(retries, 20));
    return delay.compareTo(properties.retryMaxDelay()) > 0 ? properties.retryMaxDelay() : delay;
  }

  private LocalDateTime now() { return LocalDateTime.now(clock); }
}
