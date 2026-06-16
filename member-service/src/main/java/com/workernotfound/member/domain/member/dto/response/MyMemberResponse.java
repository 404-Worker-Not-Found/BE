package com.workernotfound.member.domain.member.dto.response;

import com.workernotfound.member.domain.member.entity.enums.MemberRole;
import com.workernotfound.member.domain.member.entity.enums.MemberStatus;
import java.time.LocalDateTime;

public record MyMemberResponse(
	Long memberId,
	String name,
	String email,
	String phoneNumber,
	MemberRole role,
	MemberStatus status,
	LocalDateTime joinedAt,
	OwnerProfileResponse ownerProfile,
	WorkerProfileResponse workerProfile
) {
}
