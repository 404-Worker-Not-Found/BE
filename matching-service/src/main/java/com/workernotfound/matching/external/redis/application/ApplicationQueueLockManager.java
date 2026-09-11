package com.workernotfound.matching.external.redis.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApplicationQueueLockManager {

	private static final String KEY_FORMAT = "matching:applications:job:%d:lock";
	private static final Duration LOCK_TTL = Duration.ofSeconds(30);
	private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(5);
	private static final long RETRY_INTERVAL_MILLIS = 20L;
	private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) == ARGV[1] then
			return redis.call('DEL', KEYS[1])
		end
		return 0
		""", Long.class);

	private final StringRedisTemplate redisTemplate;

	public void execute(Long jobPostId, Runnable task) {
		String key = KEY_FORMAT.formatted(jobPostId);
		String token = UUID.randomUUID().toString();
		acquire(key, token);
		try {
			task.run();
		} finally {
			release(key, token);
		}
	}

	private void acquire(String key, String token) {
		long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
		do {
			if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, LOCK_TTL))) {
				return;
			}
			waitBeforeRetry();
		} while (System.nanoTime() < deadline);
		throw new IllegalStateException("지원 대기열 잠금을 획득하지 못했습니다.");
	}

	private void waitBeforeRetry() {
		try {
			Thread.sleep(RETRY_INTERVAL_MILLIS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("지원 대기열 잠금 대기가 중단됐습니다.", exception);
		}
	}

	private void release(String key, String token) {
		redisTemplate.execute(RELEASE_SCRIPT, List.of(key), token);
	}
}
