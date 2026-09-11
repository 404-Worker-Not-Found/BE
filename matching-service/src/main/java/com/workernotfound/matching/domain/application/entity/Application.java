package com.workernotfound.matching.domain.application.entity;

import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(
	name = "applications",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_applications_job_post_worker",
			columnNames = {"job_post_id", "worker_member_id"}
		),
		@UniqueConstraint(
			name = "uk_applications_admission",
			columnNames = "job_application_admission_id"
		)
	},
	indexes = {
		@Index(
			name = "idx_applications_worker_applied",
			columnList = "worker_member_id, applied_at, id"
		),
		@Index(
			name = "idx_applications_job_status_applied",
			columnList = "job_post_id, status, applied_at, id"
		)
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Application extends BaseEntity {

	@Column(name = "job_post_id", nullable = false, updatable = false)
	private Long jobPostId;

	@Column(name = "worker_member_id", nullable = false, updatable = false)
	private Long workerMemberId;

	@Column(name = "job_application_admission_id", nullable = false, updatable = false)
	private Long jobApplicationAdmissionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ApplicationStatus status;

	@Column(name = "applied_at", nullable = false, updatable = false)
	private LocalDateTime appliedAt;

	@Version
	@Column(nullable = false)
	private Long version;

	@Column(nullable = false)
	private Long revision;

	@Builder
	private Application(
		Long jobPostId,
		Long workerMemberId,
		Long jobApplicationAdmissionId,
		LocalDateTime appliedAt
	) {
		this.jobPostId = jobPostId;
		this.workerMemberId = workerMemberId;
		this.jobApplicationAdmissionId = jobApplicationAdmissionId;
		this.status = ApplicationStatus.APPLIED;
		this.appliedAt = appliedAt;
		this.revision = 1L;
	}

	public void cancel() {
		if (status != ApplicationStatus.APPLIED) {
			throw new IllegalStateException("접수된 지원만 취소할 수 있습니다.");
		}
		this.status = ApplicationStatus.CANCELED;
		this.revision++;
	}
}
