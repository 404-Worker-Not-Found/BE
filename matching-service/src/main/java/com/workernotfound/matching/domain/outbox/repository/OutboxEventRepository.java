package com.workernotfound.matching.domain.outbox.repository;

import com.workernotfound.matching.domain.outbox.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	Optional<OutboxEvent> findByEventId(String eventId);
}
