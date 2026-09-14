package com.workernotfound.matching.external.client.confirmation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record SeatReservationResponse(
	String reservationId,
	Long jobPostId,
	Long ownerMemberId,
	LocalDate workDate,
	LocalTime startTime,
	LocalTime endTime,
	BigDecimal lockedAmount,
	String currency,
	LocalDateTime reservedAt,
	LocalDateTime expiresAt
) {
}
