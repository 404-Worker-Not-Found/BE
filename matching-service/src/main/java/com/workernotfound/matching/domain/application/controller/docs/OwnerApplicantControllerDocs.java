package com.workernotfound.matching.domain.application.controller.docs;

import com.workernotfound.matching.domain.application.dto.request.OwnerApplicantListRequest;
import com.workernotfound.matching.domain.application.dto.request.OwnerApplicantDetailRequest;
import com.workernotfound.matching.domain.application.dto.response.OwnerApplicantListResponse;
import com.workernotfound.matching.domain.application.dto.response.OwnerApplicantResponse;
import com.workernotfound.matching.global.config.OpenApiConfig;
import com.workernotfound.matching.global.response.ApiResponse;
import com.workernotfound.matching.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Owner Applicant", description = "점주 지원자 조회 API")
public interface OwnerApplicantControllerDocs {

	@Operation(
		summary = "공고 지원자 목록 조회",
		description = "본인 공고의 접수된 지원자를 완료된 점수 묶음 기준으로 조회합니다. 다음 페이지에서도 같은 scoreBatchId를 사용하면 정렬 기준이 고정됩니다.",
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "200",
			description = "지원자 목록 조회 성공",
			useReturnTypeSchema = true
		),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "점주가 아니거나 다른 점주의 지원", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "점수 묶음 없음", content = @Content)
	})
	ResponseEntity<ApiResponse<OwnerApplicantListResponse>> getApplicants(
		@Parameter(hidden = true) AuthenticatedMember member,
		@Parameter(description = "공고 ID", required = true) Long jobPostId,
		OwnerApplicantListRequest request
	);

	@Operation(
		summary = "공고 지원자 상세 조회",
		description = "본인 공고에 접수된 지원자의 프로필 요약과 점수 근거를 조회합니다.",
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "200",
			description = "지원자 상세 조회 성공",
			useReturnTypeSchema = true
		),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "점주가 아니거나 다른 점주의 지원", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "점수 묶음 없음", content = @Content)
	})
	ResponseEntity<ApiResponse<OwnerApplicantResponse>> getApplicant(
		@Parameter(hidden = true) AuthenticatedMember member,
		@Parameter(description = "공고 ID", required = true) Long jobPostId,
		@Parameter(description = "지원 ID", required = true) Long applicationId,
		OwnerApplicantDetailRequest request
	);
}
