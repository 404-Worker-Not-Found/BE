package com.workernotfound.member.domain.member.dto.response;

import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.entity.enums.MemberStatus;

public record MemberInternalResponse(
	Long memberId,
	String name,
	String email,
	String phoneNumber,
	MemberRole role,
	MemberStatus status
) {
}
