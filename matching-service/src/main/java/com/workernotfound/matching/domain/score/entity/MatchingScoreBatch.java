package com.workernotfound.matching.domain.score.entity;

import com.workernotfound.matching.domain.score.entity.enums.ScoreBatchStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Entity
@Table(
	name = "matching_score_batches",
	indexes = @Index(
		name = "idx_matching_score_batches_job_status_completed",
		columnList = "job_post_id, status, completed_at"
	)
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchingScoreBatch {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "job_post_id", nullable = false, updatable = false)
	private Long jobPostId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ScoreBatchStatus status;

	@Column(name = "policy_version", nullable = false, updatable = false, length = 50)
	private String policyVersion;

	@Column(name = "model_version", updatable = false, length = 50)
	private String modelVersion;

	@Column(name = "started_at", nullable = false, updatable = false)
	private LocalDateTime startedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	private MatchingScoreBatch(
		Long jobPostId,
		String policyVersion,
		String modelVersion,
		LocalDateTime startedAt
	) {
		this.jobPostId = jobPostId;
		this.status = ScoreBatchStatus.CALCULATING;
		this.policyVersion = policyVersion;
		this.modelVersion = modelVersion;
		this.startedAt = startedAt;
	}

	public void complete(LocalDateTime completedAt) {
		validateCalculating();
		this.status = ScoreBatchStatus.READY;
		this.completedAt = completedAt;
	}

	public void fail(LocalDateTime completedAt) {
		validateCalculating();
		this.status = ScoreBatchStatus.FAILED;
		this.completedAt = completedAt;
	}

	private void validateCalculating() {
		if (status != ScoreBatchStatus.CALCULATING) {
			throw new IllegalStateException("계산 중인 점수 배치만 상태를 변경할 수 있습니다.");
		}
	}
}
