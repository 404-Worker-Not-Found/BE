package com.workernotfound.matching.domain.outbox;

import com.workernotfound.matching.domain.application.event.ApplicationEvent;
import com.workernotfound.matching.domain.application.repository.ApplicationRepository;
import com.workernotfound.matching.domain.application.repository.ApplicationStatusHistoryRepository;
import com.workernotfound.matching.domain.application.service.ApplicationCommandService;
import com.workernotfound.matching.domain.outbox.service.OutboxEventCommandService;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ApplicationOutboxTransactionTests extends IntegrationTestSupport {

	@Autowired
	private ApplicationCommandService applicationCommandService;

	@Autowired
	private ApplicationRepository applicationRepository;

	@Autowired
	private ApplicationStatusHistoryRepository historyRepository;

	@MockitoBean
	private OutboxEventCommandService outboxEventCommandService;

	@Test
	void rollsBackApplicationAndHistoryWhenOutboxSaveFails() {
		long applicationCount = applicationRepository.count();
		long historyCount = historyRepository.count();
		when(outboxEventCommandService.saveApplicationEvent(any(ApplicationEvent.class)))
			.thenThrow(new IllegalStateException("outbox save failed"));

		assertThatThrownBy(() -> applicationCommandService.create(
			9910L,
			9920L,
			9930L,
			LocalDateTime.now(),
			"00000000-0000-0000-0000-000000000001"
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("outbox save failed");
		assertThat(applicationRepository.count()).isEqualTo(applicationCount);
		assertThat(historyRepository.count()).isEqualTo(historyCount);
	}
}
