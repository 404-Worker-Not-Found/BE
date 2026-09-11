package com.workernotfound.matching.external.redis.application;

import com.workernotfound.matching.support.IntegrationTestSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationQueueLockManagerTests extends IntegrationTestSupport {

	@Autowired
	private ApplicationQueueLockManager lockManager;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private ExecutorService executorService;

	@BeforeEach
	void setUp() {
		try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
			connection.serverCommands().flushDb();
		}
		executorService = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void tearDown() {
		executorService.shutdownNow();
	}

	@Test
	void serializesTasksForSameJobPost() throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1);
		Future<?> first = executorService.submit(() -> lockManager.execute(10L, () -> {
			firstEntered.countDown();
			await(releaseFirst);
		}));
		assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();

		Future<?> second = executorService.submit(() -> lockManager.execute(
			10L,
			secondEntered::countDown
		));

		assertThat(secondEntered.await(200, TimeUnit.MILLISECONDS)).isFalse();
		releaseFirst.countDown();
		first.get(2, TimeUnit.SECONDS);
		second.get(2, TimeUnit.SECONDS);
		assertThat(secondEntered.getCount()).isZero();
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(2, TimeUnit.SECONDS)) {
				throw new IllegalStateException("동시성 테스트 대기 시간이 초과됐습니다.");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}
}
