package com.workernotfound.matching.domain.matching.dto.response;

import com.workernotfound.matching.domain.matching.entity.Matching;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MatchingResponse(
	Long matchingId,
	Long applicationId,
	Long jobPostId,
	Long ownerMemberId,
	Long workerMemberId,
	String selectionType,
	String status,
	Long scoreBatchId,
	Long scoreSnapshotId,
	BigDecimal totalScore,
	LocalDateTime selectedAt,
	LocalDateTime expiresAt,
	LocalDateTime confirmedAt
) {

	public static MatchingResponse from(Matching matching) {
		return new MatchingResponse(
			matching.getId(),
			matching.getApplication().getId(),
			matching.getJobPostId(),
			matching.getOwnerMemberId(),
			matching.getWorkerMemberId(),
			matching.getSelectionType().name(),
			matching.getStatus().name(),
			matching.getScoreBatch() == null ? null : matching.getScoreBatch().getId(),
			matching.getScoreSnapshot() == null ? null : matching.getScoreSnapshot().getId(),
			matching.getScoreSnapshot() == null ? null : matching.getScoreSnapshot().getTotalScore(),
			matching.getSelectedAt(),
			matching.getExpiresAt(),
			matching.getConfirmedAt()
		);
	}
}
