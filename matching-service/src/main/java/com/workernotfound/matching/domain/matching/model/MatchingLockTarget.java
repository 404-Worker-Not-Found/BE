package com.workernotfound.matching.domain.matching.model;

public record MatchingLockTarget(
	Long applicationId,
	Long workerMemberId
) {
}
