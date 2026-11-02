package com.workernotfound.matching.domain.matching.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 자동 매칭에서 남은 자리가 있는데 제안할 후보가 없는 사실. 공고(JOB_POST) aggregate에 공고마다 한 번(revision 1) 발행한다.
 */
public record MatchingCandidatesExhaustedEvent(
	String eventId,
	String eventType,
	LocalDateTime occurredAt,
	Long aggregateId,
	String correlationId,
	Long revision,
	Integer version,
	Long jobPostId,
	Long ownerMemberId,
	Integer remainingSeats
) {
	public static final String TYPE = "MatchingCandidatesExhausted";
	private static final long REVISION = 1L;
	private static final int SCHEMA_VERSION = 1;

	public static MatchingCandidatesExhaustedEvent of(
		Long jobPostId,
		Long ownerMemberId,
		int remainingSeats,
		LocalDateTime exhaustedAt
	) {
		return new MatchingCandidatesExhaustedEvent(UUID.randomUUID().toString(), TYPE, exhaustedAt, jobPostId,
			jobPostId.toString(), REVISION, SCHEMA_VERSION, jobPostId, ownerMemberId, remainingSeats);
	}
}
