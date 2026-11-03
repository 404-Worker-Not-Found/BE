package com.workernotfound.member.domain.outbox;

import com.workernotfound.member.external.redis.MemberEventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 발행하지 않은 회원 이벤트를 짧은 DB 임대로 점유해 Redis Stream에 발행한다. Stream 기록 뒤 발행 완료 저장 전에 멈추면
 * 같은 이벤트가 다시 발행될 수 있으므로(at-least-once) 소비자는 eventId와 revision으로 중복·역순을 거른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberOutboxRelay {
	private final MemberOutboxRepository repository;
	private final MemberEventPublisher publisher;
	private final MemberOutboxProperties properties;
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

	private void publish(MemberOutboxMessage message, String token) {
		try {
			publisher.publish(message);
			repository.published(message.id(), token, now());
		} catch (RuntimeException exception) {
			log.warn("회원 이벤트 발행 실패: eventId={}, exceptionType={}", message.eventId(),
				exception.getClass().getSimpleName());
			recordFailure(message, token);
		}
	}

	private void recordFailure(MemberOutboxMessage message, String token) {
		try {
			repository.failed(message.id(), token, now().plus(retryDelay(message.retryCount())));
		} catch (RuntimeException recordFailure) {
			log.warn("회원 이벤트 실패 기록 실패, 임대 만료로 복구: eventId={}, exceptionType={}", message.eventId(),
				recordFailure.getClass().getSimpleName());
		}
	}

	private Duration retryDelay(int retries) {
		Duration delay = properties.retryBaseDelay().multipliedBy(1L << Math.min(retries, 20));
		return delay.compareTo(properties.retryMaxDelay()) > 0 ? properties.retryMaxDelay() : delay;
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}
}
