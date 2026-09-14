package com.workernotfound.matching.domain.matching.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record MatchingConfirmationState(
	Long sagaId,
	Long matchingId,
	Long applicationId,
	Long jobPostId,
	Long ownerMemberId,
	Long workerMemberId,
	String seatReservationCommandId,
	String seatConfirmationCommandId,
	String seatCompensationCommandId,
	String paymentLockCommandId,
	String paymentCompensationCommandId,
	String workCreationCommandId,
	String workCompensationCommandId,
	String chatCreationCommandId,
	String chatCompensationCommandId,
	String seatReservationId,
	String paymentId,
	String workId,
	String chatRoomId,
	LocalDate workDate,
	LocalTime startTime,
	LocalTime endTime,
	BigDecimal lockedAmount,
	String currency,
	boolean seatConsumed
) {
}
