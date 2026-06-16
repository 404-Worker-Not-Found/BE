package com.workernotfound.member.domain.worker.entity;

import com.workernotfound.member.domain.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.DayOfWeek;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
	name = "worker_available_times",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_worker_available_times_profile_time",
		columnNames = {"worker_profile_id", "day_of_week", "start_time", "end_time"}
	)
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkerAvailableTime extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "worker_profile_id", nullable = false)
	private WorkerProfile workerProfile;

	@Enumerated(EnumType.STRING)
	@Column(name = "day_of_week", nullable = false, length = 20)
	private DayOfWeek dayOfWeek;

	@Column(name = "start_time", nullable = false)
	private LocalTime startTime;

	@Column(name = "end_time", nullable = false)
	private LocalTime endTime;

	@Builder
	private WorkerAvailableTime(
		WorkerProfile workerProfile,
		DayOfWeek dayOfWeek,
		LocalTime startTime,
		LocalTime endTime
	) {
		this.workerProfile = workerProfile;
		this.dayOfWeek = dayOfWeek;
		this.startTime = startTime;
		this.endTime = endTime;
	}
}
