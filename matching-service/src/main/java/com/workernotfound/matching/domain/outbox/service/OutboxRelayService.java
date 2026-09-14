package com.workernotfound.matching.domain.outbox.service;

import com.workernotfound.matching.domain.outbox.config.OutboxRelayProperties;
import com.workernotfound.matching.domain.outbox.model.OutboxMessage;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

	private final OutboxEventRepository outboxEventRepository;
	private final OutboxRelayTransactionService transactionService;
	private final OutboxEventPublisher eventPublisher;
	private final OutboxRelayProperties properties;

	public void relay() {
		LocalDateTime now = LocalDateTime.now();
		outboxEventRepository.findRelayCandidateIds(now, PageRequest.of(0, properties.batchSize()))
			.forEach(this::relay);
	}

	private void relay(Long eventId) {
		LocalDateTime claimedAt = LocalDateTime.now();
		String leaseToken = UUID.randomUUID().toString();
		transactionService.claim(
			eventId,
			leaseToken,
			claimedAt,
			claimedAt.plus(properties.leaseDuration())
		).ifPresent(message -> publish(message, leaseToken));
	}

	private void publish(OutboxMessage message, String leaseToken) {
		try {
			eventPublisher.publish(message);
			transactionService.published(message.id(), leaseToken, LocalDateTime.now());
		} catch (RuntimeException exception) {
			LocalDateTime nextAttemptAt = LocalDateTime.now().plus(retryDelay(message.retryCount() + 1));
			transactionService.failed(message.id(), leaseToken, exception.getMessage(), nextAttemptAt);
			log.warn("Outbox 이벤트 발행에 실패했습니다. eventId={}", message.eventId(), exception);
		}
	}

	private Duration retryDelay(int retryCount) {
		long multiplier = 1L << Math.min(retryCount - 1, 20);
		Duration delay = properties.retryBaseDelay().multipliedBy(multiplier);
		return delay.compareTo(properties.retryMaxDelay()) > 0 ? properties.retryMaxDelay() : delay;
	}
}
