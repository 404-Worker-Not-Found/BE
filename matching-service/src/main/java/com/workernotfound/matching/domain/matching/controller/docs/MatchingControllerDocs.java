package com.workernotfound.matching.domain.matching.controller.docs;

import com.workernotfound.matching.domain.matching.dto.request.CreateManualMatchingRequest;
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

@Tag(name = "Matching", description = "매칭 API")
public interface MatchingControllerDocs {

	@Operation(
		summary = "수동 매칭 후보 선택",
		description = "점주가 본인 공고의 접수된 지원자를 선택해 PENDING 매칭 시도를 생성합니다.",
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "200",
			description = "수동 매칭 후보 선택 성공",
			useReturnTypeSchema = true
		),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "유효하지 않은 요청", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "점주가 아니거나 다른 점주의 지원", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "점수 묶음 없음", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "지원 상태 또는 기존 매칭 충돌", content = @Content)
	})
	ResponseEntity<ApiResponse<MatchingResponse>> createManual(
		@Parameter(hidden = true) AuthenticatedMember member,
		@Parameter(description = "공고 ID", required = true)
		@Positive(message = "공고 ID는 양수여야 합니다.") Long jobPostId,
		@Parameter(description = "지원 ID", required = true)
		@Positive(message = "지원 ID는 양수여야 합니다.") Long applicationId,
		@Valid CreateManualMatchingRequest request
	);
}
