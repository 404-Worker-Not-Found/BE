package com.workernotfound.member.domain.worker.entity;

import com.workernotfound.member.domain.common.entity.BaseEntity;
import com.workernotfound.member.domain.location.entity.Location;
import com.workernotfound.member.domain.member.entity.Member;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
	name = "worker_profiles",
	uniqueConstraints = @UniqueConstraint(name = "uk_worker_profiles_member_id", columnNames = "member_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkerProfile extends BaseEntity {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(name = "desired_hourly_wage", nullable = false)
	private Integer desiredHourlyWage;

	@Column(name = "activity_radius_km", nullable = false)
	private Integer activityRadiusKm;

	@Column(name = "immediately_available", nullable = false)
	private boolean immediatelyAvailable;

	@OneToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "base_location_id", nullable = false)
	private Location baseLocation;

	@OneToMany(mappedBy = "workerProfile", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
	private List<WorkerPreferredBusinessType> preferredBusinessTypes = new ArrayList<>();

	@OneToMany(mappedBy = "workerProfile", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
	private List<WorkerAvailableTime> availableTimes = new ArrayList<>();

	@Builder
	private WorkerProfile(
		Member member,
		Integer desiredHourlyWage,
		Integer activityRadiusKm,
		boolean immediatelyAvailable,
		Location baseLocation
	) {
		this.member = member;
		this.desiredHourlyWage = desiredHourlyWage;
		this.activityRadiusKm = activityRadiusKm;
		this.immediatelyAvailable = immediatelyAvailable;
		this.baseLocation = baseLocation;
	}

	public void addPreferredBusinessType(String businessType) {
		preferredBusinessTypes.add(WorkerPreferredBusinessType.builder()
			.workerProfile(this)
			.businessType(businessType)
			.build());
	}

	public void addAvailableTime(WorkerAvailableTime availableTime) {
		availableTimes.add(availableTime);
	}
}
