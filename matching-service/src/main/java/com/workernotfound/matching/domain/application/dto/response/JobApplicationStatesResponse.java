package com.workernotfound.matching.domain.application.dto.response;

import com.workernotfound.matching.domain.application.entity.Application;
import java.util.List;

// job-service 지원자 수 projection이 revision 비교로 반영하는 공고별 지원 상태 스냅샷
public record JobApplicationStatesResponse(
	Long jobPostId,
	List<ApplicationStateResponse> applications
) {

	public static JobApplicationStatesResponse of(Long jobPostId, List<Application> applications) {
		return new JobApplicationStatesResponse(
			jobPostId,
			applications.stream().map(ApplicationStateResponse::from).toList()
		);
	}
}
