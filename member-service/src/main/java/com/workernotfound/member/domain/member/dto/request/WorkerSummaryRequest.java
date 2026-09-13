package com.workernotfound.member.domain.member.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WorkerSummaryRequest(
	@NotEmpty
	@Size(max = 100)
	List<@Positive Long> memberIds
) {
}
