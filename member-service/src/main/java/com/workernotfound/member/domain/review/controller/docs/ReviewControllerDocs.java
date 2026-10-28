package com.workernotfound.member.domain.review.controller.docs;

import com.workernotfound.member.domain.review.dto.request.WriteReviewRequest;
import com.workernotfound.member.domain.review.dto.response.PendingReviewsResponse;
import com.workernotfound.member.domain.review.dto.response.ReceivedReviewsResponse;
import com.workernotfound.member.domain.review.dto.response.ReviewResponse;
import com.workernotfound.member.global.config.OpenApiConfig;
import com.workernotfound.member.global.response.ApiResponse;
import com.workernotfound.member.global.security.AuthenticatedMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;

@Tag(name = "Review", description = "근무 완료 기반 상호 리뷰 API")
public interface ReviewControllerDocs {

	@Operation(
		summary = "리뷰 작성",
		description = "완료된 근무의 점주·알바생이 상대방에게 평점(1~5)과 선택 코멘트(500자 이하)를 남깁니다. "
			+ "근무 완료 후 작성 기간(기본 14일) 안에 근무당 한 번 쓸 수 있습니다. 같은 내용의 재요청은 처음 리뷰를 돌려주고, "
			+ "수정·삭제는 지원하지 않습니다.",
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "리뷰 작성 성공", useReturnTypeSchema = true),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 값 오류", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "근무 참여자가 아님(REVIEW-403-001) 또는 탈퇴 진행 중인 회원", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "리뷰할 수 있는 완료 근무가 아님(REVIEW-404-001)", content = @Content),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 다른 내용으로 작성함(REVIEW-409-001) 또는 작성 기간 지남(REVIEW-409-002)", content = @Content)
	})
	ResponseEntity<ApiResponse<ReviewResponse>> writeReview(
		@Parameter(hidden = true) AuthenticatedMember member,
		@Valid WriteReviewRequest request
	);

	@Operation(
		summary = "작성할 리뷰 목록",
		description = "내가 참여해 완료됐고 작성 기간 안이며 아직 리뷰하지 않은 근무를 최근 완료 순으로 조회합니다.",
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "페이지 값 오류", content = @Content)
	})
	ResponseEntity<ApiResponse<PendingReviewsResponse>> getPendingReviews(
		@Parameter(hidden = true) AuthenticatedMember member,
		@Parameter(description = "페이지(0부터)") @Min(0) int page,
		@Parameter(description = "페이지 크기(1~100)") @Min(1) @Max(100) int size
	);

	@Operation(
		summary = "받은 리뷰 조회",
		description = "회원이 받은 리뷰 수, 평균 평점(소수 첫째 자리 반올림, 없으면 null)과 리뷰 목록을 최근 순으로 조회합니다. "
			+ "작성자 회원 ID는 보여 주지 않고 작성자가 점주인지 알바생인지만 보여 줍니다.",
		security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공", useReturnTypeSchema = true),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "회원 없음", content = @Content)
	})
	ResponseEntity<ApiResponse<ReceivedReviewsResponse>> getReceivedReviews(
		@Parameter(description = "회원 ID") @Positive Long memberId,
		@Parameter(description = "페이지(0부터)") @Min(0) int page,
		@Parameter(description = "페이지 크기(1~100)") @Min(1) @Max(100) int size
	);
}
