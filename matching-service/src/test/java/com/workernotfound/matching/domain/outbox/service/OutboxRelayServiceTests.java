package com.workernotfound.matching.domain.outbox.service;

import com.workernotfound.matching.domain.outbox.entity.OutboxEvent;
import com.workernotfound.matching.domain.outbox.entity.enums.OutboxEventStatus;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OutboxRelayServiceTests extends IntegrationTestSupport {

	@Autowired
	private OutboxRelayService outboxRelayService;

	@Autowired
	private OutboxRelayTransactionService transactionService;

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private OutboxEventPublisher eventPublisher;

	@BeforeEach
	void setUp() {
		outboxEventRepository.deleteAll();
		clearInvocations(eventPublisher);
	}

	@Test
	void publishesPendingEventAndMarksItPublished() {
		OutboxEvent event = saveEvent(10L, 1L);

		outboxRelayService.relay();

		OutboxEvent published = find(event);
		assertThat(published.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		assertThat(published.getPublishedAt()).isNotNull();
		assertThat(published.getLeaseToken()).isNull();
		verify(eventPublisher).publish(any());
	}

	@Test
	void retriesFailedEventAfterBackoff() {
		OutboxEvent event = saveEvent(11L, 1L);
		doThrow(new IllegalStateException("redis unavailable"))
			.when(eventPublisher).publish(any());

		outboxRelayService.relay();

		OutboxEvent failed = find(event);
		assertThat(failed.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
		assertThat(failed.getRetryCount()).isOne();
		assertThat(failed.getLastError()).isEqualTo("redis unavailable");
		assertThat(failed.getNextAttemptAt()).isAfter(LocalDateTime.now().minusSeconds(1));

		outboxRelayService.relay();
		verify(eventPublisher, times(1)).publish(any());

		makeDue(event.getId());
		doNothing().when(eventPublisher).publish(any());
		outboxRelayService.relay();

		assertThat(find(event).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		verify(eventPublisher, times(2)).publish(any());
	}

	@Test
	void expiredLeaseCanBeClaimedAgain() {
		OutboxEvent event = saveEvent(12L, 1L);
		LocalDateTime now = LocalDateTime.now();
		assertThat(transactionService.claim(event.getId(), "first-lease", now, now.plusMinutes(1))).isPresent();

		outboxRelayService.relay();
		verify(eventPublisher, times(0)).publish(any());

		jdbcTemplate.update(
			"update outbox_events set lease_expires_at = ? where id = ?",
			LocalDateTime.now().minusSeconds(1),
			event.getId()
		);
		outboxRelayService.relay();

		assertThat(find(event).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		verify(eventPublisher).publish(any());
	}

	@Test
	void preservesRevisionOrderWithinAggregate() {
		OutboxEvent first = saveEvent(13L, 1L);
		OutboxEvent second = saveEvent(13L, 2L);
		doThrow(new IllegalStateException("redis unavailable"))
			.when(eventPublisher).publish(any());

		outboxRelayService.relay();

		assertThat(find(first).getStatus()).isEqualTo(OutboxEventStatus.FAILED);
		assertThat(find(second).getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		verify(eventPublisher, times(1)).publish(any());
	}

	@Test
	void concurrentRelaysPublishClaimedEventOnce() throws Exception {
		OutboxEvent event = saveEvent(14L, 1L);
		CountDownLatch publishing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			publishing.countDown();
			if (!release.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("동시 relay 테스트 대기 시간이 초과되었습니다.");
			}
			return null;
		}).when(eventPublisher).publish(any());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = executor.submit(outboxRelayService::relay);
			assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
			Future<?> second = executor.submit(outboxRelayService::relay);
			second.get(5, TimeUnit.SECONDS);
			release.countDown();
			first.get(5, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			executor.shutdownNow();
		}

		assertThat(find(event).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		verify(eventPublisher, times(1)).publish(any());
	}

	private OutboxEvent saveEvent(Long aggregateId, Long revision) {
		return outboxEventRepository.saveAndFlush(OutboxEvent.builder()
			.eventId(UUID.randomUUID().toString())
			.aggregateType("APPLICATION")
			.aggregateId(aggregateId)
			.eventType("ApplicationSubmitted")
			.correlationId(UUID.randomUUID().toString())
			.revision(revision)
			.schemaVersion(1)
			.payload("{}")
			.occurredAt(LocalDateTime.now().minusMinutes(1).plusNanos(revision))
			.build());
	}

	private OutboxEvent find(OutboxEvent event) {
		return outboxEventRepository.findById(event.getId()).orElseThrow();
	}

	private void makeDue(Long eventId) {
		jdbcTemplate.update(
			"update outbox_events set next_attempt_at = ? where id = ?",
			LocalDateTime.now().minusSeconds(1),
			eventId
		);
	}
}
