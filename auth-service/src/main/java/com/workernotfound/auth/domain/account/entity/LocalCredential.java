package com.workernotfound.auth.domain.account.entity;

import com.workernotfound.auth.domain.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
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
	name = "local_credentials",
	uniqueConstraints = @UniqueConstraint(name = "uk_local_credentials_auth_account_id", columnNames = "auth_account_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LocalCredential extends BaseEntity {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "auth_account_id", nullable = false)
	private AuthAccount authAccount;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Column(name = "password_changed_at", nullable = false)
	private LocalDateTime passwordChangedAt;

	@Builder
	private LocalCredential(AuthAccount authAccount, String passwordHash, LocalDateTime passwordChangedAt) {
		this.authAccount = authAccount;
		this.passwordHash = passwordHash;
		this.passwordChangedAt = passwordChangedAt;
	}

	public void changePassword(String passwordHash, LocalDateTime changedAt) {
		this.passwordHash = passwordHash;
		this.passwordChangedAt = changedAt;
	}
}
