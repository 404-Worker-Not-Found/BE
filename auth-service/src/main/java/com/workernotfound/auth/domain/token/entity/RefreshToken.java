package com.workernotfound.auth.domain.token.entity;

import com.workernotfound.auth.domain.account.entity.AuthAccount;
import com.workernotfound.auth.domain.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
	name = "refresh_tokens",
	uniqueConstraints = @UniqueConstraint(name = "uk_refresh_tokens_token_hash", columnNames = "token_hash"),
	indexes = @Index(name = "idx_refresh_tokens_account_device", columnList = "auth_account_id, device_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "auth_account_id", nullable = false)
	private AuthAccount authAccount;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "device_id", nullable = false, length = 100)
	private String deviceId;

	@Column(name = "token_hash", nullable = false, length = 255)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "revoked_at")
	private LocalDateTime revokedAt;

	@Column(name = "replaced_by_token_id")
	private Long replacedByTokenId;

	@Column(name = "last_used_at")
	private LocalDateTime lastUsedAt;

	@Builder
	private RefreshToken(
		AuthAccount authAccount,
		Long memberId,
		String deviceId,
		String tokenHash,
		LocalDateTime expiresAt,
		LocalDateTime lastUsedAt
	) {
		this.authAccount = authAccount;
		this.memberId = memberId;
		this.deviceId = deviceId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.lastUsedAt = lastUsedAt;
	}

	public void markUsed(LocalDateTime usedAt) {
		this.lastUsedAt = usedAt;
	}

	public void revoke(LocalDateTime revokedAt) {
		this.revokedAt = revokedAt;
	}

	public void replace(Long replacedByTokenId, LocalDateTime revokedAt) {
		this.replacedByTokenId = replacedByTokenId;
		this.revokedAt = revokedAt;
	}
}
