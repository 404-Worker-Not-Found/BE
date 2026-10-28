package com.workernotfound.member.domain.review.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

// 평점은 1~5 정수다. 코멘트는 선택이며 앞뒤 공백을 뺀 값을 저장하고, 비어 있으면 저장하지 않는다.
public record WriteReviewRequest(
	@NotNull @Positive Long workId,
	@NotNull @Min(1) @Max(5) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer rating,
	@Size(max = 500) String comment
) {
	public String normalizedComment() {
		if (comment == null || comment.isBlank()) return null;
		return comment.strip();
	}
}
