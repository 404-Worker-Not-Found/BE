package com.workernotfound.matching.domain.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record OwnerApplicantListResponse(
	Long scoreBatchId,
	String policyVersion,
	String modelVersion,
	LocalDateTime scoreBatchCompletedAt,
	int page,
	int size,
	long totalCount,
	int totalPages,
	List<OwnerApplicantResponse> applicants
) {
}
