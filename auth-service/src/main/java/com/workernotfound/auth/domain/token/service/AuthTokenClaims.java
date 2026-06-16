package com.workernotfound.auth.domain.token.service;

import com.workernotfound.auth.domain.account.entity.enums.MemberRole;

public record AuthTokenClaims(
	Long authAccountId,
	Long memberId,
	MemberRole role
) {
}
