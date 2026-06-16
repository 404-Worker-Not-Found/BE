package com.workernotfound.auth.domain.account.entity;

import com.workernotfound.auth.domain.account.entity.enums.MemberRole;
import com.workernotfound.auth.domain.account.entity.enums.MemberStatus;
import com.workernotfound.auth.domain.account.entity.enums.SignupType;
import com.workernotfound.auth.domain.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
	name = "auth_accounts",
	uniqueConstraints = @UniqueConstraint(name = "uk_auth_accounts_email", columnNames = "email")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthAccount extends BaseEntity {

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(nullable = false, length = 255)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MemberRole role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MemberStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "signup_type", nullable = false, length = 20)
	private SignupType signupType;

	@Column(name = "last_login_at")
	private LocalDateTime lastLoginAt;

	@Builder
	private AuthAccount(Long memberId, String email, MemberRole role, SignupType signupType) {
		this.memberId = memberId;
		this.email = email;
		this.role = role;
		this.signupType = signupType;
		this.status = MemberStatus.ACTIVE;
	}

	public void recordLogin(LocalDateTime loginAt) {
		this.lastLoginAt = loginAt;
	}

	public void withdraw() {
		this.status = MemberStatus.WITHDRAWN;
	}

	public void block() {
		this.status = MemberStatus.BLOCKED;
	}
}
