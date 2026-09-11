package com.workernotfound.matching.domain.application.controller.docs;

import com.workernotfound.matching.domain.application.dto.request.ApplicationListRequest;
import com.workernotfound.matching.domain.application.dto.request.CreateApplicationRequest;
import com.workernotfound.matching.domain.application.dto.response.ApplicationListResponse;
import com.workernotfound.matching.domain.application.dto.response.ApplicationResponse;
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

@Tag(name = "Application", description = "알바생 지원 API")
public interface ApplicationControllerDocs {

	@Operation(
		summary = "공고 지원",
		description = "인증된 알바생이 모집 중인 공고에 지원합니다. 같은 공고의 접수된 지원은 기존 결과를 반환합니다.",
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "200",
			description = "지원 성공",
			useReturnTypeSchema = true
		),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 요청", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "지원할 수 없는 회원", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "공고 없음", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "지원 마감 또는 재지원 불가", content = @Content)
	})
	ResponseEntity<ApiResponse<ApplicationResponse>> create(
		@Parameter(hidden = true) AuthenticatedMember member,
		CreateApplicationRequest request
	);

	@Operation(
		summary = "내 지원 상세 조회",
		description = "인증된 알바생의 지원 한 건을 조회합니다.",
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "200",
			description = "지원 조회 성공",
			useReturnTypeSchema = true
		),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "다른 회원의 지원", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "지원 없음", content = @Content)
	})
	ResponseEntity<ApiResponse<ApplicationResponse>> getApplication(
		@Parameter(hidden = true) AuthenticatedMember member,
		@Parameter(description = "지원 ID", required = true) Long applicationId
	);

	@Operation(
		summary = "내 지원 목록 조회",
		description = "인증된 알바생의 지원 내역을 최근 지원 순으로 조회합니다.",
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "200",
			description = "지원 목록 조회 성공",
			useReturnTypeSchema = true
		)
	})
	ResponseEntity<ApiResponse<ApplicationListResponse>> getApplications(
		@Parameter(hidden = true) AuthenticatedMember member,
		ApplicationListRequest request
	);

	@Operation(
		summary = "지원 취소",
		description = "접수 상태인 본인 지원을 취소합니다. 이미 취소된 지원은 기존 결과를 반환합니다.",
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "200",
			description = "지원 취소 성공",
			useReturnTypeSchema = true
		),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "다른 회원의 지원", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "지원 없음", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "취소할 수 없는 상태", content = @Content)
	})
	ResponseEntity<ApiResponse<ApplicationResponse>> cancel(
		@Parameter(hidden = true) AuthenticatedMember member,
		@Parameter(description = "지원 ID", required = true) Long applicationId
	);
}
