package com.workernotfound.member.global.security;

import com.workernotfound.member.domain.member.entity.enums.MemberRole;

public record AuthenticatedMember(
	Long authAccountId,
	Long memberId,
	MemberRole role
) {
}
