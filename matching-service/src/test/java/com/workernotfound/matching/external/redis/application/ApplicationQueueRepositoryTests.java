package com.workernotfound.matching.external.redis.application;

import com.workernotfound.matching.support.IntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationQueueRepositoryTests extends IntegrationTestSupport {

	private static final Long JOB_POST_ID = 10L;

	@Autowired
	private ApplicationQueueRepository applicationQueueRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void cleanUpRedis() {
		try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
			connection.serverCommands().flushDb();
		}
	}

	@Test
	void addsApplicationOnlyOnce() {
		applicationQueueRepository.add(JOB_POST_ID, 1L);
		applicationQueueRepository.add(JOB_POST_ID, 1L);

		assertThat(applicationQueueRepository.findApplicationIds(JOB_POST_ID))
			.containsExactly(1L);
	}

	@Test
	void removesApplication() {
		applicationQueueRepository.add(JOB_POST_ID, 1L);

		applicationQueueRepository.remove(JOB_POST_ID, 1L);

		assertThat(applicationQueueRepository.findApplicationIds(JOB_POST_ID)).isEmpty();
	}

	@Test
	void replacesApplications() {
		applicationQueueRepository.add(JOB_POST_ID, 1L);

		applicationQueueRepository.replace(JOB_POST_ID, List.of(2L, 3L));

		assertThat(applicationQueueRepository.findApplicationIds(JOB_POST_ID))
			.containsExactlyInAnyOrder(2L, 3L);
	}
}
