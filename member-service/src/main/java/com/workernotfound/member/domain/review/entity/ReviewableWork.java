package com.workernotfound.member.domain.review.entity;

import com.workernotfound.member.domain.review.entity.enums.ReviewerRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// work-service가 완료를 알린 근무. 이 근무의 점주와 알바생만 서로 리뷰할 수 있다.
@Getter
@Entity
@Table(name = "reviewable_works")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewableWork {

	@Id
	@Column(name = "work_id")
	private Long workId;

	@Column(name = "event_id", nullable = false, updatable = false, length = 128)
	private String eventId;

	@Column(name = "job_post_id", nullable = false, updatable = false)
	private Long jobPostId;

	@Column(name = "owner_member_id", nullable = false, updatable = false)
	private Long ownerMemberId;

	@Column(name = "worker_member_id", nullable = false, updatable = false)
	private Long workerMemberId;

	@Column(name = "work_date", updatable = false)
	private LocalDate workDate;

	@Column(name = "completed_at", nullable = false, updatable = false)
	private LocalDateTime completedAt;

	@Column(name = "received_at", nullable = false, updatable = false)
	private LocalDateTime receivedAt;

	@Builder
	private ReviewableWork(Long workId, String eventId, Long jobPostId, Long ownerMemberId, Long workerMemberId,
			LocalDate workDate, LocalDateTime completedAt, LocalDateTime receivedAt) {
		this.workId = workId;
		this.eventId = eventId;
		this.jobPostId = jobPostId;
		this.ownerMemberId = ownerMemberId;
		this.workerMemberId = workerMemberId;
		this.workDate = workDate;
		this.completedAt = completedAt;
		this.receivedAt = receivedAt;
	}

	public boolean isParticipant(Long memberId) {
		return ownerMemberId.equals(memberId) || workerMemberId.equals(memberId);
	}

	public ReviewerRole roleOf(Long memberId) {
		return ownerMemberId.equals(memberId) ? ReviewerRole.OWNER : ReviewerRole.WORKER;
	}

	public Long counterpartOf(Long memberId) {
		return ownerMemberId.equals(memberId) ? workerMemberId : ownerMemberId;
	}

	// 완료 시각에서 작성 기간이 지난 시각. 이 시각과 같거나 지나면 쓸 수 없다.
	public LocalDateTime writableUntil(Duration writePeriod) {
		return completedAt.plus(writePeriod);
	}

	// 같은 근무를 다시 받았을 때 처음 기록과 같은 근무인지 본다. eventId는 재발행 시 다를 수 있어 비교하지 않는다.
	public boolean describesSameWork(ReviewableWork other) {
		return workId.equals(other.workId) && jobPostId.equals(other.jobPostId)
			&& ownerMemberId.equals(other.ownerMemberId) && workerMemberId.equals(other.workerMemberId)
			&& Objects.equals(workDate, other.workDate);
	}
}
