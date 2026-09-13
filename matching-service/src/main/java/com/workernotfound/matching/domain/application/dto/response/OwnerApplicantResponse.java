package com.workernotfound.matching.domain.application.dto.response;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
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
		return new OwnerApplicantResponse(
			application.getId(),
			application.getWorkerMemberId(),
			workerName,
			workerName != null,
			application.getStatus().name(),
			application.getAppliedAt(),
			priorityRank,
			snapshot == null ? "UNSCORED" : snapshot.getCalculationStatus().name(),
			snapshot == null ? null : snapshot.getTotalScore(),
			snapshot == null ? null : snapshot.getAppliedTimeScore(),
			snapshot == null ? null : snapshot.getRatingScore(),
			snapshot == null ? null : snapshot.getExperienceScore(),
			snapshot == null ? null : snapshot.getActivityScore(),
			snapshot == null ? null : snapshot.getArrivalScore(),
			snapshot == null ? null : snapshot.getNoShowScore(),
			snapshot == null ? null : snapshot.getExpectedArrivalMinutes(),
			snapshot == null ? null : snapshot.getNoShowProbability(),
			missingInputs,
			snapshot == null ? null : snapshot.getCalculatedAt()
		);
	}
}
