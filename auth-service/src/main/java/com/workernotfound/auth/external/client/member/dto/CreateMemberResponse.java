package com.workernotfound.auth.external.client.member.dto;

public record CreateMemberResponse(
	Long memberId,
	String email,
	String phoneNumber,
	MemberRole role,
	MemberStatus status
) {
}
