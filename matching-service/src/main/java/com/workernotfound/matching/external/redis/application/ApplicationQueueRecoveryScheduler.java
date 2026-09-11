package com.workernotfound.matching.external.redis.application;

import com.workernotfound.matching.domain.application.service.ApplicationQueueRecoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationQueueRecoveryScheduler {

	private final ApplicationQueueRecoveryService recoveryService;

	@Scheduled(
		fixedDelayString = "${matching.application-queue.recovery-interval}",
		initialDelayString = "${matching.application-queue.recovery-initial-delay}"
	)
	public void recover() {
		try {
			recoveryService.recover();
		} catch (RuntimeException exception) {
			log.warn("지원 대기열 복구에 실패했습니다.", exception);
		}
	}
}
