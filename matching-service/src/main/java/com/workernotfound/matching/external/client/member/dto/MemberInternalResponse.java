package com.workernotfound.matching.external.client.member.dto;

public record MemberInternalResponse(
	Long memberId,
	String role,
	String status
) {
}
