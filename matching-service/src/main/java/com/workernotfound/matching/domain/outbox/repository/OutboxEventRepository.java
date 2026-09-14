package com.workernotfound.matching.domain.outbox.repository;

import com.workernotfound.matching.domain.outbox.entity.OutboxEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	Optional<OutboxEvent> findByEventId(String eventId);

	@Query(
		value = """
			select event.id
			from outbox_events event
			where event.status in ('PENDING', 'FAILED')
				and event.next_attempt_at <= :now
				and (event.lease_expires_at is null or event.lease_expires_at <= :now)
				and not exists (
					select 1
					from outbox_events earlier
					where earlier.aggregate_type = event.aggregate_type
						and earlier.aggregate_id = event.aggregate_id
						and earlier.revision < event.revision
						and earlier.status <> 'PUBLISHED'
				)
			order by event.occurred_at asc, event.id asc
			""",
		nativeQuery = true
	)
	List<Long> findRelayCandidateIds(LocalDateTime now, Pageable pageable);

	@Modifying
	@Query(
		value = """
			update outbox_events
			set lease_token = :leaseToken, lease_expires_at = :leaseExpiresAt
			where id = :eventId
				and status in ('PENDING', 'FAILED')
				and next_attempt_at <= :now
				and (lease_expires_at is null or lease_expires_at <= :now)
			""",
		nativeQuery = true
	)
	int claim(Long eventId, String leaseToken, LocalDateTime now, LocalDateTime leaseExpiresAt);

	Optional<OutboxEvent> findByIdAndLeaseToken(Long id, String leaseToken);
}
