package com.workernotfound.matching.domain.matching.entity;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingSelectionType;
import com.workernotfound.matching.domain.matching.entity.enums.MatchingStatus;
import com.workernotfound.matching.domain.score.entity.MatchingScoreBatch;
import com.workernotfound.matching.domain.score.entity.MatchingScoreSnapshot;
import com.workernotfound.matching.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
	name = "matchings",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_matchings_application",
		columnNames = "application_id"
	),
	indexes = {
		@Index(name = "idx_matchings_job_status_selected", columnList = "job_post_id, status, selected_at, id"),
		@Index(name = "idx_matchings_worker_status_selected", columnList = "worker_member_id, status, selected_at, id"),
		@Index(name = "idx_matchings_owner_job_status", columnList = "owner_member_id, job_post_id, status")
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Matching extends BaseEntity {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
		name = "application_id",
		nullable = false,
		updatable = false,
		foreignKey = @ForeignKey(name = "fk_matchings_application")
	)
	private Application application;

	@Column(name = "job_post_id", nullable = false, updatable = false)
	private Long jobPostId;

	@Column(name = "owner_member_id", nullable = false, updatable = false)
	private Long ownerMemberId;

	@Column(name = "worker_member_id", nullable = false, updatable = false)
	private Long workerMemberId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(
		name = "score_batch_id",
		updatable = false,
		foreignKey = @ForeignKey(name = "fk_matchings_score_batch")
	)
	private MatchingScoreBatch scoreBatch;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(
		name = "score_snapshot_id",
		updatable = false,
		foreignKey = @ForeignKey(name = "fk_matchings_score_snapshot")
	)
	private MatchingScoreSnapshot scoreSnapshot;

	@Enumerated(EnumType.STRING)
	@Column(name = "selection_type", nullable = false, updatable = false, length = 20)
	private MatchingSelectionType selectionType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MatchingStatus status;

	@Column(name = "selected_at", nullable = false, updatable = false)
	private LocalDateTime selectedAt;

	@Column(name = "expires_at")
	private LocalDateTime expiresAt;

	@Column(name = "confirmed_at")
	private LocalDateTime confirmedAt;

	@Version
	@Column(nullable = false)
	private Long version;

	@Column(nullable = false)
	private Long revision;

	@Builder
	private Matching(
		Application application,
		MatchingScoreBatch scoreBatch,
		MatchingScoreSnapshot scoreSnapshot,
		MatchingSelectionType selectionType,
		LocalDateTime selectedAt,
		LocalDateTime expiresAt
	) {
		this.application = application;
		this.jobPostId = application.getJobPostId();
		this.ownerMemberId = application.getOwnerMemberId();
		this.workerMemberId = application.getWorkerMemberId();
		this.scoreBatch = scoreBatch;
		this.scoreSnapshot = scoreSnapshot;
		this.selectionType = selectionType;
		this.status = MatchingStatus.PENDING;
		this.selectedAt = selectedAt;
		this.expiresAt = expiresAt;
		this.revision = 1L;
	}

	public void cancel() {
		if (status != MatchingStatus.PENDING) {
			throw new IllegalStateException("대기 중인 매칭만 취소할 수 있습니다.");
		}
		this.status = MatchingStatus.CANCELED;
		this.revision++;
	}

	public void decline() {
		if (status != MatchingStatus.PENDING) {
			throw new IllegalStateException("대기 중인 매칭만 거절할 수 있습니다.");
		}
		this.status = MatchingStatus.DECLINED;
		this.revision++;
	}
}
