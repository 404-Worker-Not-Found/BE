package com.workernotfound.matching.external.client.confirmation.dto;

import java.math.BigDecimal;

public record PaymentLockRequest(
	Long matchingId,
	Long jobPostId,
	Long ownerMemberId,
	Long workerMemberId,
	BigDecimal amount,
	String currency
) {
}
