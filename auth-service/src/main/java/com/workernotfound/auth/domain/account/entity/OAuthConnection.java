package com.workernotfound.auth.domain.account.entity;

import com.workernotfound.auth.domain.account.entity.enums.OAuthProvider;
import com.workernotfound.auth.domain.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
	name = "oauth_connections",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_oauth_connections_provider_user",
		columnNames = {"provider", "provider_user_id"}
	)
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthConnection extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "auth_account_id", nullable = false)
	private AuthAccount authAccount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OAuthProvider provider;

	@Column(name = "provider_user_id", nullable = false, length = 255)
	private String providerUserId;

	@Column(name = "provider_email", length = 255)
	private String providerEmail;

	@Column(name = "connected_at", nullable = false)
	private LocalDateTime connectedAt;

	@Builder
	private OAuthConnection(
		AuthAccount authAccount,
		OAuthProvider provider,
		String providerUserId,
		String providerEmail,
		LocalDateTime connectedAt
	) {
		this.authAccount = authAccount;
		this.provider = provider;
		this.providerUserId = providerUserId;
		this.providerEmail = providerEmail;
		this.connectedAt = connectedAt;
	}
}
