package com.workernotfound.matching.external.redis.application;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreBatchRepository;
import com.workernotfound.matching.domain.score.repository.MatchingScoreSnapshotRepository;
import com.workernotfound.matching.domain.score.policy.ApplicationTimeScorePolicy;
import com.workernotfound.matching.external.redis.score.MatchingScoreQueueRepository;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

class ApplicationQueueFailureTests extends IntegrationTestSupport {

	@Autowired
	private ApplicationCommandService applicationCommandService;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private ApplicationStatusHistoryRepository historyRepository;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private MatchingScoreSnapshotRepository scoreSnapshotRepository;

	@Autowired
	private MatchingScoreBatchRepository scoreBatchRepository;

	@MockitoBean
	private ApplicationQueueRepository applicationQueueRepository;

	@MockitoBean
	private MatchingScoreQueueRepository matchingScoreQueueRepository;

	@BeforeEach
	void cleanUpPersistence() {
		scoreSnapshotRepository.deleteAll();
		scoreBatchRepository.deleteAll();
		outboxEventRepository.deleteAll();
		historyRepository.deleteAll();
		applicationRepository.deleteAll();
	}

	@AfterEach
	void tearDown() {
		cleanUpPersistence();
	}

	@Test
	void commitsApplicationWhenRedisUpdateFails() {
		doThrow(new IllegalStateException("Redis connection failed"))
			.when(applicationQueueRepository).add(eq(10L), anyLong());

		Application application = applicationCommandService.create(
			10L,
			20L,
			30L,
			LocalDateTime.now(),
			"correlation-id"
		);

		assertThat(applicationRepository.findById(application.getId())).isPresent();
		assertThat(historyRepository.findByApplicationIdOrderByRevisionAsc(application.getId()))
			.hasSize(1);
		assertThat(outboxEventRepository.count()).isEqualTo(1L);
	}

	@Test
	void commitsCancellationWhenRedisUpdateFails() {
		Application application = applicationCommandService.create(
			10L,
			20L,
			30L,
			LocalDateTime.now(),
			"create-correlation-id"
		);
		doThrow(new IllegalStateException("Redis connection failed"))
			.when(applicationQueueRepository).remove(10L, application.getId());

		applicationCommandService.cancel(application.getId(), 20L, "cancel-correlation-id");

		assertThat(applicationRepository.findById(application.getId()))
			.get()
			.extracting(Application::getStatus)
			.isEqualTo(ApplicationStatus.CANCELED);
		assertThat(historyRepository.findByApplicationIdOrderByRevisionAsc(application.getId()))
			.hasSize(2);
		assertThat(outboxEventRepository.count()).isEqualTo(2L);
	}

	@Test
	void commitsApplicationWhenRankedQueueUpdateFails() {
		doThrow(new IllegalStateException("Redis connection failed"))
			.when(matchingScoreQueueRepository).replace(
				eq(10L),
				anyLong(),
				eq(ApplicationTimeScorePolicy.VERSION),
				anyList(),
				anyLong()
			);

		Application application = applicationCommandService.create(
			10L,
			20L,
			30L,
			LocalDateTime.now(),
			"correlation-id"
		);

		assertThat(applicationRepository.findById(application.getId())).isPresent();
		assertThat(historyRepository.findByApplicationIdOrderByRevisionAsc(application.getId()))
			.hasSize(1);
		assertThat(outboxEventRepository.count()).isEqualTo(1L);
		assertThat(scoreBatchRepository.count()).isEqualTo(1L);
		assertThat(scoreSnapshotRepository.count()).isEqualTo(1L);
	}
}
