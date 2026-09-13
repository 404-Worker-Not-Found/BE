package com.workernotfound.matching.domain.matching.controller.docs;

import com.workernotfound.matching.domain.matching.dto.request.MatchingListRequest;
import com.workernotfound.matching.domain.matching.dto.response.MatchingListResponse;
import com.workernotfound.matching.domain.matching.dto.response.MatchingResponse;
import com.workernotfound.matching.global.config.OpenApiConfig;
import com.workernotfound.matching.global.response.ApiResponse;
import com.workernotfound.matching.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;

@Tag(name = "Worker Matching", description = "알바생 매칭 제안 API")
public interface WorkerMatchingControllerDocs {

	@Operation(summary = "내 매칭 제안 목록 조회", security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
	ResponseEntity<ApiResponse<MatchingListResponse>> getMatchings(
		@Parameter(hidden = true) AuthenticatedMember member,
		@Valid MatchingListRequest request
	);

	@Operation(summary = "내 매칭 제안 상세 조회", security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "다른 알바생의 매칭", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "매칭 없음", content = @Content)
	})
	ResponseEntity<ApiResponse<MatchingResponse>> getMatching(
		@Parameter(hidden = true) AuthenticatedMember member,
		@Parameter(description = "매칭 ID", required = true)
		@Positive(message = "매칭 ID는 양수여야 합니다.") Long matchingId
	);

	@Operation(summary = "매칭 제안 거절", security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "거절 성공", useReturnTypeSchema = true),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "다른 알바생의 매칭", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "매칭 없음", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "매칭 상태 충돌", content = @Content)
	})
	ResponseEntity<ApiResponse<MatchingResponse>> decline(
		@Parameter(hidden = true) AuthenticatedMember member,
		@Parameter(description = "매칭 ID", required = true)
		@Positive(message = "매칭 ID는 양수여야 합니다.") Long matchingId
	);
}
