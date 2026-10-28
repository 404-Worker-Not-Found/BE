package com.workernotfound.member.domain.review.dto.response;

import com.workernotfound.member.domain.review.entity.MemberReview;
import com.workernotfound.member.domain.review.entity.enums.ReviewerRole;
import java.time.LocalDateTime;

public record ReviewResponse(
	Long reviewId,
	Long workId,
	Long revieweeMemberId,
	ReviewerRole reviewerRole,
	int rating,
	String comment,
	LocalDateTime createdAt
) {
	public static ReviewResponse from(MemberReview review) {
		return new ReviewResponse(review.getId(), review.getWorkId(), review.getRevieweeMemberId(),
			review.getReviewerRole(), review.getRating(), review.getComment(), review.getCreatedAt());
	}
}
