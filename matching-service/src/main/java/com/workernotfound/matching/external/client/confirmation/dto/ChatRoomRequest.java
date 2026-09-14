package com.workernotfound.matching.external.client.confirmation.dto;

public record ChatRoomRequest(
	Long matchingId,
	Long jobPostId,
	Long ownerMemberId,
	Long workerMemberId,
	String workId
) {
}
