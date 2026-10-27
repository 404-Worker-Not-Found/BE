package com.workernotfound.member.domain.review.controller;

import com.workernotfound.member.domain.review.controller.docs.ReviewControllerDocs;
import com.workernotfound.member.domain.review.dto.request.WriteReviewRequest;
import com.workernotfound.member.domain.review.dto.response.PendingReviewsResponse;
import com.workernotfound.member.domain.review.dto.response.ReceivedReviewsResponse;
import com.workernotfound.member.domain.review.dto.response.ReviewResponse;
import com.workernotfound.member.domain.review.service.ReviewCommandService;
import com.workernotfound.member.domain.review.service.ReviewQueryService;
import com.workernotfound.member.global.response.ApiResponse;
import com.workernotfound.member.global.security.AuthenticatedMember;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/members")
public class ReviewController implements ReviewControllerDocs {
	private final ReviewCommandService reviewCommandService;
	private final ReviewQueryService reviewQueryService;

	@Override
	@PostMapping("/me/reviews")
	public ResponseEntity<ApiResponse<ReviewResponse>> writeReview(
		@AuthenticationPrincipal AuthenticatedMember member,
		@Valid @RequestBody WriteReviewRequest request
	) {
		return ResponseEntity.ok(ApiResponse.success(
			ReviewResponse.from(reviewCommandService.write(member.memberId(), request))));
	}

	@Override
	@GetMapping("/me/reviews/pending")
	public ResponseEntity<ApiResponse<PendingReviewsResponse>> getPendingReviews(
		@AuthenticationPrincipal AuthenticatedMember member,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		return ResponseEntity.ok(ApiResponse.success(reviewQueryService.pending(member.memberId(), page, size)));
	}

	@Override
	@GetMapping("/{memberId}/reviews")
	public ResponseEntity<ApiResponse<ReceivedReviewsResponse>> getReceivedReviews(
		@PathVariable Long memberId,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		return ResponseEntity.ok(ApiResponse.success(reviewQueryService.received(memberId, page, size)));
	}
}
