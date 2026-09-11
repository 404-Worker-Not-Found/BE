package com.workernotfound.matching.domain.outbox;

import com.workernotfound.matching.domain.outbox.entity.OutboxEvent;
import com.workernotfound.matching.domain.outbox.entity.enums.OutboxEventStatus;
import com.workernotfound.matching.domain.outbox.repository.OutboxEventRepository;
import com.workernotfound.matching.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class OutboxEventRepositoryTests extends IntegrationTestSupport {

	@Autowired
	private OutboxEventRepository outboxEventRepository;

	@Test
	void savesPendingEventWithJsonPayload() {
		OutboxEvent event = outboxEventRepository.saveAndFlush(event(
			"00000000-0000-0000-0000-000000000001",
			1L,
			1L,
			"ApplicationSubmitted"
		));

		assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(event.getRetryCount()).isZero();
		assertThat(event.getPayload()).isEqualTo("{\"applicationId\":1}");
		assertThat(outboxEventRepository.findByEventId(event.getEventId())).contains(event);
	}

	@Test
	void rejectsDuplicateEventId() {
		String eventId = "00000000-0000-0000-0000-000000000002";
		outboxEventRepository.saveAndFlush(event(eventId, 2L, 1L, "ApplicationSubmitted"));

		assertThatThrownBy(() -> outboxEventRepository.saveAndFlush(
			event(eventId, 3L, 1L, "ApplicationSubmitted")
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsSecondEventForSameAggregateRevision() {
		outboxEventRepository.saveAndFlush(event(
			"00000000-0000-0000-0000-000000000003",
			4L,
			1L,
			"ApplicationSubmitted"
		));

		assertThatThrownBy(() -> outboxEventRepository.saveAndFlush(event(
			"00000000-0000-0000-0000-000000000004",
			4L,
			1L,
			"ApplicationCanceled"
		))).isInstanceOf(DataIntegrityViolationException.class);
	}

	private OutboxEvent event(String eventId, Long aggregateId, Long revision, String eventType) {
		return OutboxEvent.builder()
			.eventId(eventId)
			.aggregateType("APPLICATION")
			.aggregateId(aggregateId)
			.eventType(eventType)
			.correlationId("10000000-0000-0000-0000-000000000001")
			.revision(revision)
			.schemaVersion(1)
			.payload("{\"applicationId\":1}")
			.occurredAt(LocalDateTime.of(2026, 9, 11, 10, 0))
			.build();
	}
}
