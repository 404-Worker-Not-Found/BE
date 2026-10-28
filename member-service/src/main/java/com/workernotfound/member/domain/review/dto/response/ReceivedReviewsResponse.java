package com.workernotfound.member.domain.review.dto.response;

import com.workernotfound.member.domain.review.entity.MemberReview;
import com.workernotfound.member.domain.review.entity.enums.ReviewerRole;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 회원이 받은 리뷰. 작성자 회원 ID는 노출하지 않고 작성자가 맡은 쪽만 보여 준다.
public record ReceivedReviewsResponse(
	Long memberId,
	long reviewCount,
	BigDecimal averageRating,
	List<ReceivedReview> reviews,
	int page,
	int size,
	long totalCount
) {
	public record ReceivedReview(Long reviewId, ReviewerRole reviewerRole, int rating, String comment,
		LocalDateTime createdAt) {
		public static ReceivedReview from(MemberReview review) {
			return new ReceivedReview(review.getId(), review.getReviewerRole(), review.getRating(), review.getComment(),
				review.getCreatedAt());
		}
	}
}
