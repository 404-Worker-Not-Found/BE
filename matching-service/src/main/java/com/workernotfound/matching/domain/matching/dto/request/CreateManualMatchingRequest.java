package com.workernotfound.matching.domain.matching.dto.request;

import jakarta.validation.constraints.Positive;

public record CreateManualMatchingRequest(
	@Positive(message = "점수 묶음 ID는 양수여야 합니다.")
	Long scoreBatchId
) {
}
