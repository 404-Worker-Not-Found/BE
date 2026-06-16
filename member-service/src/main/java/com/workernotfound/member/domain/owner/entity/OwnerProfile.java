package com.workernotfound.member.domain.owner.entity;

import com.workernotfound.member.domain.common.entity.BaseEntity;
import com.workernotfound.member.domain.location.entity.Location;
import com.workernotfound.member.domain.member.entity.Member;
import com.workernotfound.member.domain.owner.entity.enums.BusinessVerificationStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
	name = "owner_profiles",
	uniqueConstraints = @UniqueConstraint(name = "uk_owner_profiles_member_id", columnNames = "member_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerProfile extends BaseEntity {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(name = "business_registration_number", nullable = false, length = 20)
	private String businessRegistrationNumber;

	@Column(name = "business_type", nullable = false, length = 100)
	private String businessType;

	@Enumerated(EnumType.STRING)
	@Column(name = "business_verification_status", nullable = false, length = 20)
	private BusinessVerificationStatus businessVerificationStatus;

	@OneToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "store_location_id", nullable = false)
	private Location storeLocation;
}
