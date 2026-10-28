package com.workernotfound.member.domain.review.dto.response;

import com.workernotfound.member.domain.review.entity.enums.ReviewerRole;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 내가 아직 리뷰하지 않았고 작성 기간 안인 완료 근무.
public record PendingReviewsResponse(
	List<PendingReview> works,
	int page,
	int size,
	long totalCount
) {
	public record PendingReview(Long workId, Long jobPostId, LocalDate workDate, ReviewerRole myRole,
		Long revieweeMemberId, LocalDateTime completedAt, LocalDateTime writableUntil) {
	}
}
