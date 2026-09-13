package com.workernotfound.matching.domain.application.dto.response;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.domain.score.entity.enums.ScoreCalculationStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OwnerApplicantResponse(
	Long applicationId,
	Long workerMemberId,
	String workerName,
	boolean profileAvailable,
	String status,
	LocalDateTime appliedAt,
	Long priorityRank,
	String scoreCalculationStatus,
	BigDecimal totalScore,
	BigDecimal appliedTimeScore,
	BigDecimal ratingScore,
	BigDecimal experienceScore,
	BigDecimal activityScore,
	BigDecimal arrivalScore,
	BigDecimal noShowScore,
	Integer expectedArrivalMinutes,
	BigDecimal noShowProbability,
	List<String> missingInputs,
	LocalDateTime scoreCalculatedAt
) {

	public static OwnerApplicantResponse from(
		Application application,
		MatchingScoreSnapshot snapshot,
		String workerName,
		Long priorityRank,
		List<String> missingInputs
	) {
		boolean scoreReady = snapshot != null
			&& snapshot.getCalculationStatus() == ScoreCalculationStatus.READY;
		return new OwnerApplicantResponse(
			application.getId(),
			application.getWorkerMemberId(),
			workerName,
			workerName != null,
			application.getStatus().name(),
			application.getAppliedAt(),
			scoreReady ? priorityRank : null,
			snapshot == null ? "UNSCORED" : snapshot.getCalculationStatus().name(),
			scoreReady ? snapshot.getTotalScore() : null,
			scoreReady ? snapshot.getAppliedTimeScore() : null,
			scoreReady ? snapshot.getRatingScore() : null,
			scoreReady ? snapshot.getExperienceScore() : null,
			scoreReady ? snapshot.getActivityScore() : null,
			scoreReady ? snapshot.getArrivalScore() : null,
			scoreReady ? snapshot.getNoShowScore() : null,
			scoreReady ? snapshot.getExpectedArrivalMinutes() : null,
			scoreReady ? snapshot.getNoShowProbability() : null,
			missingInputs,
			snapshot == null ? null : snapshot.getCalculatedAt()
		);
	}
}
