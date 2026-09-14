package com.workernotfound.matching.external.redis.outbox;

import com.workernotfound.matching.domain.outbox.service.OutboxRelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

	private final OutboxRelayService outboxRelayService;

	@Scheduled(
		fixedDelayString = "${matching.outbox-relay.interval}",
		initialDelayString = "${matching.outbox-relay.initial-delay}"
	)
	public void relay() {
		try {
			outboxRelayService.relay();
		} catch (RuntimeException exception) {
			log.warn("Outbox relay 실행에 실패했습니다.", exception);
		}
	}
}
