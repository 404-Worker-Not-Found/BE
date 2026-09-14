package com.workernotfound.matching.external.client.confirmation.dto;

public record SeatReservationRequest(
	Long matchingId,
	Long applicationId,
	Long workerMemberId
) {
}
