package com.workernotfound.matching.domain.score.entity;

import com.workernotfound.matching.domain.application.entity.Application;
import com.workernotfound.matching.domain.score.entity.enums.ScoreCalculationStatus;
import com.workernotfound.matching.domain.score.model.MatchingScoreResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(
	name = "matching_score_snapshots",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_matching_score_snapshots_batch_application",
		columnNames = {"score_batch_id", "application_id"}
	),
	indexes = {
		@Index(
			name = "idx_matching_score_snapshots_application_calculated",
			columnList = "application_id, calculated_at"
		),
		@Index(
			name = "idx_matching_score_snapshots_batch_score",
			columnList = "score_batch_id, total_score DESC, application_id"
		)
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchingScoreSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "score_batch_id", nullable = false, updatable = false)
	private MatchingScoreBatch scoreBatch;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "application_id", nullable = false, updatable = false)
	private Application application;

	@Enumerated(EnumType.STRING)
	@Column(name = "calculation_status", nullable = false, length = 20)
	private ScoreCalculationStatus calculationStatus;

	@Column(name = "total_score", precision = 9, scale = 4)
	private BigDecimal totalScore;

	@Column(name = "applied_time_score", precision = 9, scale = 4)
	private BigDecimal appliedTimeScore;

	@Column(name = "rating_score", precision = 9, scale = 4)
	private BigDecimal ratingScore;

	@Column(name = "experience_score", precision = 9, scale = 4)
	private BigDecimal experienceScore;

	@Column(name = "activity_score", precision = 9, scale = 4)
	private BigDecimal activityScore;

	@Column(name = "arrival_score", precision = 9, scale = 4)
	private BigDecimal arrivalScore;

	@Column(name = "no_show_score", precision = 9, scale = 4)
	private BigDecimal noShowScore;

	@Column(name = "expected_arrival_minutes")
	private Integer expectedArrivalMinutes;

	@Column(name = "no_show_probability", precision = 6, scale = 5)
	private BigDecimal noShowProbability;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_snapshot", nullable = false, updatable = false, columnDefinition = "json")
	private String inputSnapshot;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "missing_inputs", updatable = false, columnDefinition = "json")
	private String missingInputs;

	@Column(name = "calculated_at", nullable = false)
	private LocalDateTime calculatedAt;

	@Builder
	private MatchingScoreSnapshot(
		MatchingScoreBatch scoreBatch,
		Application application,
		String inputSnapshot,
		String missingInputs,
		LocalDateTime calculatedAt
	) {
		this.scoreBatch = scoreBatch;
		this.application = application;
		this.calculationStatus = ScoreCalculationStatus.PENDING;
		this.inputSnapshot = inputSnapshot;
		this.missingInputs = missingInputs;
		this.calculatedAt = calculatedAt;
	}

	public void complete(MatchingScoreResult result, LocalDateTime calculatedAt) {
		validatePending();
		this.totalScore = result.totalScore();
		this.appliedTimeScore = result.appliedTimeScore();
		this.ratingScore = result.ratingScore();
		this.experienceScore = result.experienceScore();
		this.activityScore = result.activityScore();
		this.arrivalScore = result.arrivalScore();
		this.noShowScore = result.noShowScore();
		this.expectedArrivalMinutes = result.expectedArrivalMinutes();
		this.noShowProbability = result.noShowProbability();
		this.calculationStatus = ScoreCalculationStatus.READY;
		this.calculatedAt = calculatedAt;
	}

	public void fail(LocalDateTime calculatedAt) {
		validatePending();
		this.calculationStatus = ScoreCalculationStatus.FAILED;
		this.calculatedAt = calculatedAt;
	}

	private void validatePending() {
		if (calculationStatus != ScoreCalculationStatus.PENDING) {
			throw new IllegalStateException("계산 대기 중인 점수만 상태를 변경할 수 있습니다.");
		}
	}
}
