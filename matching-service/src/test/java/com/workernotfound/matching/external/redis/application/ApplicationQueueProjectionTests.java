package com.workernotfound.matching.external.redis.application;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.event.ApplicationEvent;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.repository.RecruitmentStateRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.application.service.ApplicationQueueRecoveryService;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
	private ApplicationQueueEventListener applicationQueueEventListener;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private ApplicationStatusHistoryRepository historyRepository;

	@Autowired
	private RecruitmentStateRepository recruitmentStateRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private MatchingScoreSnapshotRepository scoreSnapshotRepository;

	@Autowired
	private MatchingScoreBatchRepository scoreBatchRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void cleanUp() {
		scoreSnapshotRepository.deleteAll();
		scoreBatchRepository.deleteAll();
		outboxEventRepository.deleteAll();
		historyRepository.deleteAll();
		applicationRepository.deleteAll();
		recruitmentStateRepository.deleteAll();
		try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
			connection.serverCommands().flushDb();
		}
	}

	@AfterEach
	void tearDown() {
		cleanUp();
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
		applicationQueueRepository.add(10L, 999L, 1L);
		applicationQueueRepository.add(11L, canceled.getId(), 1L);

		recoveryService.recover();

		assertThat(applicationQueueRepository.findApplicationIds(10L))
			.containsExactly(applied.getId());
		assertThat(applicationQueueRepository.findApplicationIds(11L)).isEmpty();
	}

	@Test
	void handlesDuplicateApplicationEventsIdempotently() {
		Application application = createApplication(10L, 20L, 30L);
		ApplicationEvent event = ApplicationEvent.submitted(application, "duplicate-correlation-id");
		long scoreBatchCount = scoreBatchRepository.count();
		applicationQueueRepository.replace(10L, List.of(), 2L);

		applicationQueueEventListener.updateQueue(event);
		applicationQueueEventListener.updateQueue(event);

		assertThat(applicationQueueRepository.findApplicationIds(10L))
			.containsExactly(application.getId());
		assertThat(scoreBatchRepository.count()).isEqualTo(scoreBatchCount);
	}

	@Test
	void rejectsApplicationQueueWritesWithOlderFenceToken() {
		applicationQueueRepository.replace(10L, List.of(1L), 2L);

		applicationQueueRepository.add(10L, 2L, 1L);
		applicationQueueRepository.remove(10L, 1L, 1L);
		applicationQueueRepository.replace(10L, List.of(3L), 1L);

		assertThat(applicationQueueRepository.findApplicationIds(10L)).containsExactly(1L);
	}

	private Application createApplication(Long jobPostId, Long workerMemberId, Long admissionId) {
		return applicationCommandService.create(
			jobPostId,
			workerMemberId,
			100L,
			admissionId,
			1L,
			LocalDateTime.now(),
			"create-correlation-id-" + admissionId
		);
	}
}
