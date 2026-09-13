package com.workernotfound.matching.external.redis.score;

public record MatchingScoreQueueMetadata(
	Long scoreBatchId,
	String policyVersion
) {
}
