package com.workernotfound.matching.domain.matching.dto.response;

import com.workernotfound.matching.domain.matching.entity.Matching;
import java.util.List;
import org.springframework.data.domain.Page;

public record MatchingListResponse(
	int page,
	int size,
	long totalCount,
	int totalPages,
	List<MatchingResponse> matchings
) {

	public static MatchingListResponse from(Page<Matching> page) {
		return new MatchingListResponse(
			page.getNumber(),
			page.getSize(),
			page.getTotalElements(),
			page.getTotalPages(),
			page.getContent().stream().map(MatchingResponse::from).toList()
		);
	}
}
