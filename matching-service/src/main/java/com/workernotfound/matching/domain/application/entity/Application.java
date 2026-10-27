package com.workernotfound.matching.domain.application.entity;

import com.workernotfound.matching.domain.application.entity.enums.ApplicationStatus;
import com.workernotfound.matching.domain.application.exception.ApplicationErrorCode;
import com.workernotfound.matching.global.entity.BaseEntity;
import com.workernotfound.matching.global.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
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
		name = "applications",
		uniqueConstraints = {
			@UniqueConstraint(
					name = "uk_applications_job_post_worker",
					columnNames = {"job_post_id", "worker_member_id"}),
			@UniqueConstraint(
					name = "uk_applications_admission",
					columnNames = "job_application_admission_id")
		},
		indexes = {
			@Index(
					name = "idx_applications_worker_applied",
					columnList = "worker_member_id, applied_at, id"),
			@Index(
					name = "idx_applications_job_status_applied",
					columnList = "job_post_id, status, applied_at, id")
		})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Application extends BaseEntity {

	@Column(name = "job_post_id", nullable = false, updatable = false)
	private Long jobPostId;

	@Column(name = "worker_member_id", nullable = false, updatable = false)
	private Long workerMemberId;

	@Column(name = "owner_member_id", updatable = false)
	private Long ownerMemberId;

	@Column(name = "job_application_admission_id", nullable = false, updatable = false)
	private Long jobApplicationAdmissionId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ApplicationStatus status;

	@Column(name = "applied_at", nullable = false, updatable = false)
	private LocalDateTime appliedAt;

	// 지원할 때 받은 근무 시작 일시. V12 이전 지원은 NULL이다.
	@Column(name = "work_starts_at", updatable = false)
	private LocalDateTime workStartsAt;

	@Version
	@Column(nullable = false)
	private Long version;

	@Column(nullable = false)
	private Long revision;

	@Builder
	private Application(
			Long jobPostId,
			Long workerMemberId,
			Long ownerMemberId,
			Long jobApplicationAdmissionId,
			LocalDateTime appliedAt,
			LocalDateTime workStartsAt) {
		this.jobPostId = jobPostId;
		this.workerMemberId = workerMemberId;
		this.ownerMemberId = ownerMemberId;
		this.jobApplicationAdmissionId = jobApplicationAdmissionId;
		this.status = ApplicationStatus.APPLIED;
		this.appliedAt = appliedAt;
		this.workStartsAt = workStartsAt;
		this.revision = 1L;
	}

	// 근무 시작 시각과 같거나 지났으면 시작한 것으로 본다. 근무 일시를 모르는 지원은 검사하지 않는다.
	public boolean hasWorkStarted(LocalDateTime now) {
		return workStartsAt != null && !now.isBefore(workStartsAt);
	}

	public void cancel() {
		if (status != ApplicationStatus.APPLIED) {
			throw new BusinessException(ApplicationErrorCode.APPLICATION_STATE_CONFLICT);
		}
		this.status = ApplicationStatus.CANCELED;
		this.revision++;
	}

	public void select() {
		if (status != ApplicationStatus.APPLIED) {
			throw new BusinessException(ApplicationErrorCode.APPLICATION_STATE_CONFLICT);
		}
		this.status = ApplicationStatus.SELECTED;
		this.revision++;
	}

	public void cancelSelectedWork() {
		if (status != ApplicationStatus.SELECTED) throw new BusinessException(ApplicationErrorCode.APPLICATION_STATE_CONFLICT);
		status = ApplicationStatus.CANCELED;
		revision++;
	}

	public void reject() {
		if (status != ApplicationStatus.APPLIED) {
			throw new BusinessException(ApplicationErrorCode.APPLICATION_STATE_CONFLICT);
		}
		this.status = ApplicationStatus.REJECTED;
		this.revision++;
	}
}
