package com.workernotfound.matching.external.client.member.dto;

import java.util.List;

public record WorkerSummaryRequest(
	List<Long> memberIds
) {
}
