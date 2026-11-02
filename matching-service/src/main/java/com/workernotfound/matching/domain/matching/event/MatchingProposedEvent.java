package com.workernotfound.matching.domain.matching.event;

import com.workernotfound.matching.domain.matching.entity.Matching;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 알바생에게 매칭 제안이 생긴 사실(수동·자동·폴백). 매칭 aggregate의 첫 revision이므로 같은 매칭의 MatchConfirmed보다 먼저 발행된다.
 * 응답 제한시간(expiresAt)은 자동·폴백 제안에만 있다.
 */
public record MatchingProposedEvent(
	String eventId,
	String eventType,
	LocalDateTime occurredAt,
	Long aggregateId,
	String correlationId,
	Long revision,
	Integer version,
	Long matchingId,
	Long applicationId,
	Long jobPostId,
	Long ownerMemberId,
	Long workerMemberId,
	String selectionType,
	LocalDateTime expiresAt
) {
	public static final String TYPE = "MatchingProposed";
	private static final int SCHEMA_VERSION = 1;

	public static MatchingProposedEvent of(Matching matching) {
		return new MatchingProposedEvent(
			UUID.randomUUID().toString(),
			TYPE,
			matching.getSelectedAt(),
			matching.getId(),
			matching.getId().toString(),
			matching.getRevision(),
			SCHEMA_VERSION,
			matching.getId(),
			matching.getApplication().getId(),
			matching.getJobPostId(),
			matching.getOwnerMemberId(),
			matching.getWorkerMemberId(),
			matching.getSelectionType().name(),
			matching.getExpiresAt()
		);
	}
}
