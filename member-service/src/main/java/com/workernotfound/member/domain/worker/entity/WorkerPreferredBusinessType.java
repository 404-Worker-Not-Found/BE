package com.workernotfound.member.domain.worker.entity;

import com.workernotfound.member.domain.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
	name = "worker_preferred_business_types",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_worker_preferred_business_types_profile_type",
		columnNames = {"worker_profile_id", "business_type"}
	)
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkerPreferredBusinessType extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "worker_profile_id", nullable = false)
	private WorkerProfile workerProfile;

	@Column(name = "business_type", nullable = false, length = 100)
	private String businessType;
}
