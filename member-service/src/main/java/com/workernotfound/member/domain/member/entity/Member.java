package com.workernotfound.member.domain.member.entity;

import com.workernotfound.member.domain.common.entity.BaseEntity;
import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.entity.enums.MemberStatus;
import com.workernotfound.member.domain.owner.entity.OwnerProfile;
import com.workernotfound.member.domain.worker.entity.WorkerProfile;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
	name = "members",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_members_email", columnNames = "email"),
		@UniqueConstraint(name = "uk_members_phone_number", columnNames = "phone_number")
	}
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

	@Column(nullable = false, length = 50)
	private String name;

	@Column(length = 255)
	private String email;

	@Column(name = "phone_number", length = 20)
	private String phoneNumber;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MemberRole role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MemberStatus status;

	@Column(name = "joined_at", nullable = false)
	private LocalDateTime joinedAt;

	@Column(name = "withdrawn_at")
	private LocalDateTime withdrawnAt;

	@Column(name = "blocked_at")
	private LocalDateTime blockedAt;

	@OneToOne(mappedBy = "member", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
	private OwnerProfile ownerProfile;

	@OneToOne(mappedBy = "member", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
	private WorkerProfile workerProfile;

	@Builder
	private Member(String name, String email, String phoneNumber, MemberRole role) {
		this.name = name;
		this.email = email;
		this.phoneNumber = phoneNumber;
		this.role = role;
		this.status = MemberStatus.ACTIVE;
		this.joinedAt = LocalDateTime.now();
	}

    public void prepareWithdrawal() { status = MemberStatus.WITHDRAWING; }
    public void releaseWithdrawal() {
        if (status == MemberStatus.WITHDRAWING) status = MemberStatus.ACTIVE;
    }
    public void erasePersonalData() {
        status = MemberStatus.WITHDRAWN;
        withdrawnAt = LocalDateTime.now();
        name = "탈퇴회원"; email = null; phoneNumber = null;
        ownerProfile = null; workerProfile = null;
    }

	public void updateContact(boolean isEmail, String target) {
        if (isEmail) this.email = target;
        else this.phoneNumber = target;
    }

	public void updateName(String name) {
		this.name = name;
	}

	public void registerOwnerProfile(OwnerProfile ownerProfile) {
		this.ownerProfile = ownerProfile;
	}

	public void registerWorkerProfile(WorkerProfile workerProfile) {
		this.workerProfile = workerProfile;
	}
}
