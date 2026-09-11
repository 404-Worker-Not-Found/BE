package com.workernotfound.matching.external.client.job.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record ApplicationAdmissionResponse(
	Long admissionId,
	Long jobPostId,
	Long jobVersion,
	Long ownerMemberId,
	Long categoryId,
	LocalDate workDate,
	LocalTime startTime,
	LocalTime endTime,
	BigDecimal latitude,
	BigDecimal longitude,
	LocalDateTime admittedAt,
	LocalDateTime expiresAt
) {
}
