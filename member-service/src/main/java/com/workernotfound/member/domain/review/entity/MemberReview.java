package com.workernotfound.member.domain.review.entity;

import com.workernotfound.member.domain.review.entity.enums.ReviewerRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 근무 참여자가 상대방에게 남긴 리뷰. 수정·삭제는 아직 정책이 없어 지원하지 않는다.
@Getter
@Entity
@Table(name = "member_reviews")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberReview {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "work_id", nullable = false, updatable = false)
	private Long workId;

	@Column(name = "reviewer_member_id", nullable = false, updatable = false)
	private Long reviewerMemberId;

	@Column(name = "reviewee_member_id", nullable = false, updatable = false)
	private Long revieweeMemberId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reviewer_role", nullable = false, updatable = false, length = 20)
	private ReviewerRole reviewerRole;

	@Column(nullable = false, updatable = false)
	private int rating;

	@Column(updatable = false, length = 500)
	private String comment;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	private MemberReview(Long workId, Long reviewerMemberId, Long revieweeMemberId, ReviewerRole reviewerRole,
			int rating, String comment, LocalDateTime createdAt) {
		this.workId = workId;
		this.reviewerMemberId = reviewerMemberId;
		this.revieweeMemberId = revieweeMemberId;
		this.reviewerRole = reviewerRole;
		this.rating = rating;
		this.comment = comment;
		this.createdAt = createdAt;
	}

	public boolean hasSameContent(int rating, String comment) {
		return this.rating == rating && Objects.equals(this.comment, comment);
	}
}
