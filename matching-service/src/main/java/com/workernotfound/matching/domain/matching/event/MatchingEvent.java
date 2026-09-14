package com.workernotfound.matching.domain.matching.event;

import com.workernotfound.matching.domain.matching.entity.Matching;
import com.workernotfound.matching.domain.matching.entity.MatchingConfirmationSaga;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

public record MatchingEvent(
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
	String workId,
	String paymentId,
	String chatRoomId,
	LocalDate workDate,
	LocalTime startTime,
	LocalTime endTime,
	BigDecimal lockedAmount,
	String currency
) {

	private static final int SCHEMA_VERSION = 1;

	public static MatchingEvent confirmed(
		Matching matching,
		MatchingConfirmationSaga saga,
		LocalDateTime confirmedAt
	) {
		return new MatchingEvent(
			UUID.randomUUID().toString(),
			"MatchConfirmed",
			confirmedAt,
			matching.getId(),
			matching.getId().toString(),
			matching.getRevision(),
			SCHEMA_VERSION,
			matching.getId(),
			matching.getApplication().getId(),
			matching.getJobPostId(),
			matching.getOwnerMemberId(),
			matching.getWorkerMemberId(),
			saga.getWorkId(),
			saga.getPaymentId(),
			saga.getChatRoomId(),
			saga.getWorkDate(),
			saga.getStartTime(),
			saga.getEndTime(),
			saga.getLockedAmount(),
			saga.getCurrency()
		);
	}
}
