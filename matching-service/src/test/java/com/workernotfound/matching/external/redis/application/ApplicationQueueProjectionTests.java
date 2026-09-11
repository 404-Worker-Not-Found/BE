package com.workernotfound.matching.external.redis.application;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.application.service.ApplicationQueueRecoveryService;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationQueueProjectionTests extends IntegrationTestSupport {

	@Autowired
	private ApplicationCommandService applicationCommandService;

	@Autowired
	private ApplicationQueueRecoveryService recoveryService;

	@Autowired
	private ApplicationQueueRepository applicationQueueRepository;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private ApplicationStatusHistoryRepository historyRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void cleanUp() {
		outboxEventRepository.deleteAll();
		historyRepository.deleteAll();
		applicationRepository.deleteAll();
		try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
			connection.serverCommands().flushDb();
		}
	}

	@Test
	void addsApplicationAfterTransactionCommit() {
		Application application = createApplication(10L, 20L, 30L);

		assertThat(applicationQueueRepository.findApplicationIds(10L))
			.containsExactly(application.getId());
	}

	@Test
	void removesCanceledApplicationAfterTransactionCommit() {
		Application application = createApplication(10L, 20L, 30L);

		applicationCommandService.cancel(application.getId(), 20L, "cancel-correlation-id");

		assertThat(applicationQueueRepository.findApplicationIds(10L)).isEmpty();
	}

	@Test
	void recoversAppliedApplicationsFromMySql() {
		Application applied = createApplication(10L, 20L, 30L);
		Application canceled = createApplication(11L, 21L, 31L);
		applicationCommandService.cancel(canceled.getId(), 21L, "cancel-correlation-id");
		applicationQueueRepository.add(10L, 999L);
		applicationQueueRepository.add(11L, canceled.getId());

		recoveryService.recover();

		assertThat(applicationQueueRepository.findApplicationIds(10L))
			.containsExactly(applied.getId());
		assertThat(applicationQueueRepository.findApplicationIds(11L)).isEmpty();
	}

	private Application createApplication(Long jobPostId, Long workerMemberId, Long admissionId) {
		return applicationCommandService.create(
			jobPostId,
			workerMemberId,
			admissionId,
			LocalDateTime.now(),
			"create-correlation-id-" + admissionId
		);
	}
}
