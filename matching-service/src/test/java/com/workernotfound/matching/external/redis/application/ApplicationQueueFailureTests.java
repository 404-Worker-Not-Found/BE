package com.workernotfound.matching.external.redis.application;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
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

	@MockitoBean
	private ApplicationQueueRepository applicationQueueRepository;

	@BeforeEach
	void cleanUpPersistence() {
		outboxEventRepository.deleteAll();
		historyRepository.deleteAll();
		applicationRepository.deleteAll();
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
}
