package com.workernotfound.matching.domain.outbox.service;

import com.workernotfound.matching.domain.outbox.entity.OutboxEvent;
import com.workernotfound.matching.domain.outbox.model.OutboxMessage;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxRelayTransactionService {

	private static final int ERROR_MAX_LENGTH = 1000;

	private final OutboxEventRepository outboxEventRepository;

	@Transactional
	public Optional<OutboxMessage> claim(
		Long eventId,
		String leaseToken,
		LocalDateTime now,
		LocalDateTime leaseExpiresAt
	) {
		if (outboxEventRepository.claim(eventId, leaseToken, now, leaseExpiresAt) == 0) {
			return Optional.empty();
		}
		return outboxEventRepository.findByIdAndLeaseToken(eventId, leaseToken)
			.map(OutboxMessage::from);
	}

	@Transactional
	public void published(Long eventId, String leaseToken, LocalDateTime publishedAt) {
		outboxEventRepository.findByIdAndLeaseToken(eventId, leaseToken)
			.ifPresent(event -> event.published(leaseToken, publishedAt));
	}

	@Transactional
	public void failed(
		Long eventId,
		String leaseToken,
		String error,
		LocalDateTime nextAttemptAt
	) {
		outboxEventRepository.findByIdAndLeaseToken(eventId, leaseToken)
			.ifPresent(event -> event.failed(leaseToken, truncate(error), nextAttemptAt));
	}

	private String truncate(String error) {
		String value = error == null ? "unknown publish error" : error;
		return value.substring(0, Math.min(value.length(), ERROR_MAX_LENGTH));
	}
}
