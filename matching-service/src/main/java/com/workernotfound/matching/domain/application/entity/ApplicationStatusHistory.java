package com.workernotfound.matching.domain.application.entity;

import com.workernotfound.matching.domain.application.entity.enums.ApplicationActorType;
import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
	name = "application_status_histories",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_application_status_histories_revision",
		columnNames = {"application_id", "revision"}
	),
	indexes = @Index(
		name = "idx_application_status_histories_changed",
		columnList = "application_id, changed_at"
	)
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationStatusHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(
		name = "application_id",
		nullable = false,
		updatable = false,
		foreignKey = @ForeignKey(name = "fk_application_status_histories_application")
	)
	private Application application;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status", length = 20, updatable = false)
	private ApplicationStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false, length = 20, updatable = false)
	private ApplicationStatus toStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "actor_type", nullable = false, length = 20, updatable = false)
	private ApplicationActorType actorType;

	@Column(name = "actor_member_id", updatable = false)
	private Long actorMemberId;

	@Column(name = "reason_code", length = 50, updatable = false)
	private String reasonCode;

	@Column(name = "reason_detail", length = 500, updatable = false)
	private String reasonDetail;

	@Column(nullable = false, updatable = false)
	private Long revision;

	@Column(name = "changed_at", nullable = false, updatable = false)
	private LocalDateTime changedAt;

	@Builder
	private ApplicationStatusHistory(
		Application application,
		ApplicationStatus fromStatus,
		ApplicationStatus toStatus,
		ApplicationActorType actorType,
		Long actorMemberId,
		String reasonCode,
		String reasonDetail,
		Long revision,
		LocalDateTime changedAt
	) {
		this.application = application;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.actorType = actorType;
		this.actorMemberId = actorMemberId;
		this.reasonCode = reasonCode;
		this.reasonDetail = reasonDetail;
		this.revision = revision;
		this.changedAt = changedAt;
	}
}
