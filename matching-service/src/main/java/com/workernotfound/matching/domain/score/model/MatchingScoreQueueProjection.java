package com.workernotfound.matching.domain.score.model;

import java.util.List;

public record MatchingScoreQueueProjection(
	Long jobPostId,
	Long scoreBatchId,
	String policyVersion,
	List<RankedApplicationScore> scores
) {
}
