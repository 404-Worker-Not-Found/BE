package com.workernotfound.matching.domain.application.controller.docs;

import com.workernotfound.matching.domain.application.dto.response.JobApplicationStatesResponse;
import com.workernotfound.matching.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;

@Tag(name = "Internal Recruitment", description = "공고 모집 상태 연동 API")
public interface ApplicationStateControllerDocs {

	@Operation(
		summary = "공고별 지원 상태 스냅샷",
		description = "공고의 모든 지원(상태 무관)의 applicationId·status·revision을 ID 오름차순으로 돌려준다. "
			+ "지원이 없거나 모르는 공고는 빈 목록이다."
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "내부 인증 실패", content = @Content)
	})
	ResponseEntity<ApiResponse<JobApplicationStatesResponse>> getApplicationStates(
		@Parameter(description = "공고 ID", required = true)
		@Positive(message = "공고 ID는 양수여야 합니다.") Long jobPostId
	);
}
