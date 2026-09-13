package com.workernotfound.matching.domain.application.model;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;

public record OwnerApplicantRow(
	Application application,
	MatchingScoreSnapshot scoreSnapshot
) {
}
